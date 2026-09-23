package console;

import api.ChatHandler;
import api.NetworkClient;
import models.Clock;
import models.Message;
import models.Peer;
import models.Scoreboard;
import sync.MutualExclusion;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads local commands on a dedicated thread, leaving HTTP request handling free to run normally.
 * Commands: help, chat <nodeId> <message>, score <player> <delta>, show, quit.
 */
public final class ConsoleController implements Runnable {
    private final int nodeId;
    private final List<Peer> peers;
    private final Clock clock;
    private final ChatHandler chatHandler;
    private final MutualExclusion mutex;
    private final Scoreboard scoreboard;
    private final NetworkClient network;
    private final BufferedReader input;
    private final PrintStream output;
    private final Runnable shutdown;

    public ConsoleController(int nodeId, List<Peer> peers, Clock clock, ChatHandler chatHandler,
                             MutualExclusion mutex, Scoreboard scoreboard, NetworkClient network, Runnable shutdown) {
        this(nodeId, peers, clock, chatHandler, mutex, scoreboard, network, System.in, System.out, shutdown);
    }

    ConsoleController(int nodeId, List<Peer> peers, Clock clock, ChatHandler chatHandler,
                      MutualExclusion mutex, Scoreboard scoreboard, NetworkClient network,
                      InputStream input, PrintStream output, Runnable shutdown) {
        this.nodeId = nodeId;
        this.peers = peers;
        this.clock = clock;
        this.chatHandler = chatHandler;
        this.mutex = mutex;
        this.scoreboard = scoreboard;
        this.network = network;
        this.input = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        this.output = output;
        this.shutdown = shutdown;
    }

    /** Starts a daemon input thread so a blocked console read never blocks server request workers. */
    public void start() {
        Thread thread = new Thread(this, "node-" + nodeId + "-console");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void run() {
        printHelp();
        try {
            String line;
            while ((line = readCommand()) != null) {
                if (!handle(line)) {
                    return;
                }
            }
        } catch (IOException exception) {
            synchronized (output) {
                output.println("Console input stopped: " + exception.getMessage());
            }
        }
    }

    private String readCommand() throws IOException {
        synchronized (output) {
            output.print("Node " + nodeId + "> ");
            output.flush();
        }
        return input.readLine();
    }

    private boolean handle(String line) {
        String command = line.trim();
        if (command.isEmpty()) {
            return true;
        }
        if ("help".equalsIgnoreCase(command)) {
            printHelp();
            return true;
        }
        if ("show".equalsIgnoreCase(command)) {
            show();
            return true;
        }
        if ("quit".equalsIgnoreCase(command)) {
            synchronized (output) {
                output.println("Stopping Node " + nodeId + ".");
            }
            shutdown.run();
            return false;
        }
        if (hasCommand(command, "chat")) {
            sendChat(command);
            return true;
        }
        if (hasCommand(command, "score")) {
            queueScore(command);
            return true;
        }
        synchronized (output) {
            output.println("Unknown command. Type help for available commands.");
        }
        return true;
    }

    private static boolean hasCommand(String text, String name) {
        return text.regionMatches(true, 0, name, 0, name.length())
                && (text.length() == name.length() || Character.isWhitespace(text.charAt(name.length())));
    }

    private void sendChat(String command) {
        String[] parts = command.split("\\s+", 3);
        if (parts.length < 3 || parts[2].isBlank()) {
            printLine("Usage: chat <nodeId> <message>");
            return;
        }

        int destinationId;
        try {
            destinationId = Integer.parseInt(parts[1]);
        } catch (NumberFormatException exception) {
            printLine("nodeId must be a number from 0 to " + (peers.size() - 1) + ".");
            return;
        }
        if (destinationId < 0 || destinationId >= peers.size()) {
            printLine("nodeId must be from 0 to " + (peers.size() - 1) + ".");
            return;
        }
        if (destinationId == nodeId) {
            printLine("Choose a different node for chat delivery.");
            return;
        }

        String messageText = parts[2].trim();
        clock.tick();
        Message message = new Message(nodeId, messageText, clock.getLamportTime(), clock.getVectorClock());
        chatHandler.recordLocalMessage(message);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sender_id", message.getSenderId());
        payload.put("text", message.getText());
        payload.put("lamport", message.getLamport());
        payload.put("vector", message.getVector());
        Peer destination = peers.get(destinationId);
        network.postJson(destination, "/api/chat", payload).whenComplete((response, error) -> {
            if (error != null) {
                printLine("Chat delivery to Node " + destinationId + " failed: " + error.getMessage());
            } else if (response.statusCode() >= 200 && response.statusCode() < 300) {
                printLine("Chat delivered to Node " + destinationId + ".");
            } else {
                printLine("Chat delivery to Node " + destinationId + " returned HTTP " + response.statusCode() + ".");
            }
        });
        printLine("Chat queued for Node " + destinationId + " at " + destination + ".");
    }

    private void queueScore(String command) {
        String[] parts = command.split("\\s+", 3);
        if (parts.length != 3 || parts[1].isBlank()) {
            printLine("Usage: score <player> <delta>");
            return;
        }
        int delta;
        try {
            delta = Integer.parseInt(parts[2]);
        } catch (NumberFormatException exception) {
            printLine("delta must be an integer.");
            return;
        }
        mutex.requestCriticalSection(parts[1], delta);
        printLine("Score change queued for " + parts[1] + ": " + signed(delta)
                + ". It will be applied when this node receives the token.");
    }

    private void show() {
        List<Message> messages = chatHandler.messagesSnapshot();
        synchronized (output) {
            output.println("Local chat log for Node " + nodeId + ":");
            if (messages.isEmpty()) {
                output.println("  No messages recorded.");
            } else {
                for (Message message : messages) {
                    output.println("  [Lamport=" + message.getLamport() + ", vector="
                            + Arrays.toString(message.getVector()) + "] Node " + message.getSenderId()
                            + ": " + message.getText());
                }
            }
            output.println("Current clock: Lamport=" + clock.getLamportTime()
                    + ", vector=" + Arrays.toString(clock.getVectorClock()));
            output.println("Current scoreboard: " + scoreboard.snapshot());
        }
    }

    private void printHelp() {
        synchronized (output) {
            output.println("Console commands for Node " + nodeId + ":");
            output.println("  chat <nodeId> <message>  Send a timestamped chat message to one node.");
            output.println("  score <player> <delta>   Queue a score change until this node receives the token.");
            output.println("  show                     Display the local ordered chat log, clocks, and scoreboard.");
            output.println("  help                     Display this command list.");
            output.println("  quit                     Stop this node process.");
        }
    }

    private void printLine(String text) {
        synchronized (output) {
            output.println(text);
        }
    }

    private static String signed(int value) {
        return value >= 0 ? "+" + value : String.valueOf(value);
    }
}
