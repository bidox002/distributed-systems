# CSC 4722 Setup and Test Guide

## 1. Use the required architecture

The project remains divided exactly as the brief requests:

- `Node.java`: starts one node and its HTTP server.
- `models`: message, Lamport/vector clocks, and scoreboard state.
- `sync`: token-ring mutual exclusion and Bully election.
- `api`: REST routing and the JDK HTTP client helper.

The project uses only JDK APIs (`HttpServer` and `HttpClient`). JDK 27 is fine because the build uses `--release 11`.

## 2. Required software and packages

Install the following on every Windows laptop:

| Item | Required? | Check |
| --- | --- | --- |
| JDK 27 | Yes | `java --version` and `javac --version` both show 27. |
| Windows PowerShell | Yes | Included with supported Windows versions. |
| Git | Recommended | Run `git --version`; used to pull the same project and network file. |

Do **not** install Maven, Gradle, npm, a database, a web framework, or a JSON library. The project is intentionally dependency-free and uses only Java standard-library APIs.

## 3. Compile from a clean output folder

Run this in PowerShell from the project folder:

```powershell
Remove-Item out -Recurse -Force -ErrorAction SilentlyContinue
$javaHome = 'C:\Program Files\Java\jdk-27\bin'
$sources = Get-ChildItem src\main\java -Recurse -Filter '*.java' | ForEach-Object FullName
& "$javaHome\javac.exe" --release 11 -encoding UTF-8 -d out $sources
```

Successful compilation creates `out\Node.class` and package folders below `out`.

## 4. Configure the five-laptop network

Connect all five laptops to the same Wi-Fi or hotspot. Each laptop runs two nodes in separate PowerShell windows.

| Laptop | Node IDs | Ports |
| --- | --- |
| A - your laptop | 0, 1 | 8000, 8001 |
| B | 2, 3 | 8002, 8003 |
| C | 4, 5 | 8004, 8005 |
| D | 6, 7 | 8006, 8007 |
| E | 8, 9 | 8008, 8009 |

Complete these five steps before starting any node:

1. **Connect every laptop to one Wi-Fi or hotspot.** In Windows, open **Settings > Network & internet > Wi-Fi**, select the shared network, and enter its password. Avoid guest Wi-Fi because it can block laptop-to-laptop traffic.

   Example: all five laptops join `CSC4722-Demo`. A phone hotspot may assign addresses such as `192.168.137.10` through `192.168.137.14`.

2. **Find the Wi-Fi IPv4 address on each laptop.** Run:

   ```powershell
   ipconfig
   ```

   Copy the `IPv4 Address` under `Wireless LAN adapter Wi-Fi`. Example: `192.168.137.10`.

3. **Update the shared configuration.** Enter those five addresses in [config/nodes.properties](../config/nodes.properties). Each address must be repeated for its laptop's two nodes.

   Example for your Laptop A when its address is `192.168.137.10`:

   ```properties
   node.0.host=192.168.137.10
   node.0.port=8000
   node.1.host=192.168.137.10
   node.1.port=8001
   ```

4. **Distribute one identical configuration file.** If the configuration owner commits the file, each laptop runs:

   ```powershell
   git pull
   ```

   Otherwise, copy the completed `config\nodes.properties` file into the same location on every laptop.

5. **Open the two local ports in Windows Firewall.** Run PowerShell as Administrator. On your laptop:

```powershell
New-NetFirewallRule -DisplayName 'CSC 4722 Nodes' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8000,8001
```

Use `8002,8003` on Laptop B, `8004,8005` on C, `8006,8007` on D, and `8008,8009` on E. After nodes start, test another laptop from Laptop A:

```powershell
Test-NetConnection 192.168.137.11 -Port 8002
```

Replace `192.168.137.11` with Laptop B's Wi-Fi IPv4 address. `TcpTestSucceeded : True` confirms that the shared network and firewall rule work.

Start Node 9 first and Node 0 last. Use this command format in each terminal, replacing the bracketed values:

```powershell
& 'C:\Program Files\Java\jdk-27\bin\java.exe' -cp out Node <NODE_ID> <PORT> config\nodes.properties
```

On your laptop, start Node 1 and then Node 0:

```powershell
& 'C:\Program Files\Java\jdk-27\bin\java.exe' -cp out Node 1 8001 config\nodes.properties
& 'C:\Program Files\Java\jdk-27\bin\java.exe' -cp out Node 0 8000 config\nodes.properties
```

Node 0 begins with the token. Node 9 is the initial leader.

## 5. Verify server health before testing algorithms

### Open the browser dashboard

Once at least one node is running, open `http://localhost:8000/` (or the port of any running node) on the same laptop. To open it from another laptop, use `http://<NODE_WIFI_IP>:<PORT>/`. The dashboard shows all configured nodes, their health and known leader, and the selected node's ordered chat log, clocks, and scoreboard. Choose a node and send a chat message to deliver it to that node. The dashboard refreshes automatically every five seconds.

Keep the `gui` directory beside `config` and `out` when launching nodes. No additional software is required.

From any laptop, verify every configured node:

```powershell
.\scripts\Test-Network.ps1 -ConfigPath config\nodes.properties
```

Every response must be `{"status":"ALIVE"}`. Capture this output in `logs\integration\health.txt` for the report.
The script exits with an error if any configured node is unreachable.

To check the coordinator currently known by a node, query its leader endpoint (replace the IP and port with any running node):

```powershell
Invoke-RestMethod http://<NODE_WIFI_IP>:8000/api/leader
```

The response contains the node ID, for example `leader_id: 9`.

## 6. Demonstrate logical clocks and chat ordering

Send a known message from Node 0 to Node 1:

```powershell
Invoke-RestMethod -Method Post -ContentType 'application/json' `
  -Uri http://<NODE_1_WIFI_IP>:8001/api/chat `
  -Body '{"sender_id":0,"text":"Clock test","lamport":1,"vector":[1,0,0,0,0,0,0,0,0,0]}'
```

Expected receive state on Node 1: Lamport is `2` and the vector is `[1,1,0,0,0,0,0,0,0,0]`. The Node 1 terminal prints its ordered chat log. Repeat with messages from more than one sender and capture that output. The primary ordering is Lamport time; sender ID deterministically breaks ties.

## 7. Demonstrate token-ring mutual exclusion and duplicate rejection

Each token has a stable `token_id` and a monotonically increasing `sequence_number`. A node accepts each sequence at most once. Its synchronized receive path replaces the score snapshot and applies queued score changes while it owns the token. The console prints receipt, score updates, rejected replays, and successful hand-offs (`TOKEN_HANDOFF`).

Queue score changes on several laptops with `score <player> <delta>`. Use `show` to inspect each local scoreboard. Capture the `TOKEN_HANDOFF` lines and confirm the score tables converge.

To reproduce a duplicate delivery, copy one `TOKEN_HANDOFF` line, which gives the token ID, sequence, sender, and destination. POST that same token ID and sequence a second time to the destination. For example, substitute the observed values and destination address:

```powershell
$body = '{"token_holder":0,"token_id":"token-PASTE-ID","sequence_number":1,"scores":{}}'
Invoke-RestMethod -Method Post -ContentType 'application/json' `
  -Uri http://<DESTINATION_WIFI_IP>:<DESTINATION_PORT>/api/token -Body $body
```

Expected response: `{"status":"Duplicate Token Ignored"}` (PowerShell displays the parsed object). The destination terminal prints `rejected duplicate or stale token`; it must not print another receipt or reapply score changes for that sequence. Use the same observed sequence; do not increment it.

For failure recovery, stop the next ring node, then queue a score update on the current token holder. After the request times out, the holder logs the failed peer and retries with the next reachable node, keeping ownership until a hand-off succeeds. Restart the stopped node; it rejoins when circulation reaches it again. Confirm the score change is present after the retry and replicas converge.

## 8. Demonstrate Bully election after a leader failure

1. Confirm Node 9 announces itself as coordinator after startup.
2. Stop the Node 9 window.
3. Wait at least six seconds for health probes and election requests.
4. Check the Node 0 and Node 4 terminal output.

With Nodes 0-8 available, both terminals should print that Node 8 is the new leader. Preserve the failed health probe, election, and coordinator announcement output.

Restart Node 9 while Node 8 is coordinator. Node 9 starts an election when it rejoins, and the running nodes should converge on Node 9 as coordinator. A live higher-ID node also starts an election if it receives a coordinator announcement from a lower-ID node.

## 9. Test matrix and evidence

| ID | Test | Expected evidence |
| --- | --- | --- |
| T01 | Start ten nodes | All configured IP/port pairs start. |
| T02 | Health | Every configured node returns `ALIVE`. |
| T03 | Lamport receive | Receiver applies `max(local, incoming) + 1`. |
| T04 | Vector receive | Receiver takes element-wise maxima, then increments its own entry. |
| T05 | Chat ordering | Local log is sorted by logical timestamp. |
| T06 | Token circulation | Token moves through each active node. |
| T07 | Concurrent scores | Only token arrival applies each queued update; replicas converge. |
| T08 | Normal election | Highest available node announces coordinator. |
| T09 | Leader crash | Health failure causes a new coordinator to be elected. |
| T10 | Recovery decision | Document the chosen behavior when a stopped node returns. |

Save command output and screenshots under `logs\clocks`, `logs\token`, `logs\election`, and `logs\integration`. Use the same test IDs in the report and in team-member contribution notes.

## 10. Remaining report deliverables

Prepare three UML sequence diagrams: chat receive/clock merge, token-ring score update, and Bully election. The report must also assign ownership of each module and include the matching test evidence.
