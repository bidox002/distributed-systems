# P2P Distributed Chat and Scoreboard

CSC 4722 course project implemented with Java 11 standard-library APIs only. Ten nodes run across five laptops on the same Wi-Fi or hotspot network.

## Required software and packages

Install these on every laptop before the group session:

| Item | Required? | Why |
| --- | --- | --- |
| JDK 27 | Yes | Compiles and runs the Java 11-compatible project. |
| Windows PowerShell | Yes | Included with Windows; runs the build, firewall, and health-check commands. |
| Git | Recommended | Lets each laptop pull the same project and shared network configuration from GitHub. |

No Maven, Gradle, npm, database, HTTP framework, JSON library, or other package needs to be installed. The project uses only JDK classes such as `HttpServer`, `HttpClient`, and `Properties`.

Verify Java after installation:

```powershell
java --version
javac --version
```

Both commands should show version 27. If Java is installed but not available as `java`, use the full path shown in the commands below: `C:\Program Files\Java\jdk-27\bin`.

## Build

JDK 27 is used in Java 11 compatibility mode:

```powershell
$javaHome = 'C:\Program Files\Java\jdk-27\bin'

$sources = Get-ChildItem src\main\java -Recurse -Filter '*.java' | ForEach-Object FullName

& "$javaHome\javac.exe" --release 11 -encoding UTF-8 -d out $sources
```

## Configure the five laptops

Use the following simple allocation. Your laptop is Laptop A and runs Nodes 0 and 1.

| Laptop | Nodes | Open TCP ports |
| --- | --- | --- |
| A - your laptop | 0, 1 | 8000, 8001 |
| B | 2, 3 | 8002, 8003 |
| C | 4, 5 | 8004, 8005 |
| D | 6, 7 | 8006, 8007 |
| E | 8, 9 | 8008, 8009 |

1. **Join one network.** Connect each laptop to the same Wi-Fi router or the same phone hotspot. In Windows, open **Settings > Network & internet > Wi-Fi**, select the shared network, and enter its password. Do not use a guest network because guest networks often block laptop-to-laptop traffic.

   Example: all laptops should show the same Wi-Fi name, such as `CSC4722-Demo`. A phone hotspot may give addresses like `192.168.137.10`, `192.168.137.11`, and so on.

2. **Find each laptop's Wi-Fi IPv4 address.** On each laptop, run:

   ```powershell
   ipconfig
   ```

   Under `Wireless LAN adapter Wi-Fi`, copy the value beside `IPv4 Address`. Example:

   ```text
   IPv4 Address. . . . . . . . . . . : 192.168.137.10
   ```

   Send that address, together with the laptop letter, to the configuration owner.

3. **Fill in the shared node directory.** Replace the example addresses in [config/nodes.properties](config/nodes.properties) with the five current Wi-Fi IP addresses. A laptop's address must appear for both of its nodes.

   Example, if Laptop A has `192.168.137.10`:

   ```properties
   node.0.host=192.168.137.10
   node.0.port=8000
   node.1.host=192.168.137.10
   node.1.port=8001
   ```

   Keep each node's assigned port unchanged.

4. **Give every laptop the identical completed file.** If the configuration owner commits it to GitHub, each laptop runs:

   ```powershell
   git pull
   ```

   Alternatively, copy the completed `config\nodes.properties` file to the same path in each project folder. Do not let each laptop use a different version of this file.

5. **Allow the two local ports through Windows Firewall.** Open PowerShell as Administrator and run the command matching that laptop:

```powershell
# Laptop A - Nodes 0 and 1
New-NetFirewallRule -DisplayName 'CSC 4722 Nodes' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8000,8001

# Laptop B - Nodes 2 and 3
New-NetFirewallRule -DisplayName 'CSC 4722 Nodes' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8002,8003

# Laptop C - Nodes 4 and 5
New-NetFirewallRule -DisplayName 'CSC 4722 Nodes' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8004,8005

# Laptop D - Nodes 6 and 7
New-NetFirewallRule -DisplayName 'CSC 4722 Nodes' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8006,8007

# Laptop E - Nodes 8 and 9
New-NetFirewallRule -DisplayName 'CSC 4722 Nodes' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8008,8009
```

After the nodes start, Laptop A can check that Laptop B is reachable with:

```powershell
Test-NetConnection 192.168.137.11 -Port 8002
```

Replace the example address and port with Laptop B's configured address and Node 2's port. `TcpTestSucceeded : True` confirms network and firewall access.

## Start all ten nodes

Open two PowerShell terminals on each laptop. All commands use the shared `config\nodes.properties` file. Start in descending node order: Node 9 first and Node 0 last.

```powershell
# Laptop E
& 'C:\Program Files\Java\jdk-27\bin\java.exe' -cp out Node 9 8009 config\nodes.properties
& 'C:\Program Files\Java\jdk-27\bin\java.exe' -cp out Node 8 8008 config\nodes.properties

# Laptop D
& 'C:\Program Files\Java\jdk-27\bin\java.exe' -cp out Node 7 8007 config\nodes.properties
& 'C:\Program Files\Java\jdk-27\bin\java.exe' -cp out Node 6 8006 config\nodes.properties

# Laptop C
& 'C:\Program Files\Java\jdk-27\bin\java.exe' -cp out Node 5 8005 config\nodes.properties
& 'C:\Program Files\Java\jdk-27\bin\java.exe' -cp out Node 4 8004 config\nodes.properties

# Laptop B
& 'C:\Program Files\Java\jdk-27\bin\java.exe' -cp out Node 3 8003 config\nodes.properties
& 'C:\Program Files\Java\jdk-27\bin\java.exe' -cp out Node 2 8002 config\nodes.properties

# Laptop A - your laptop
& 'C:\Program Files\Java\jdk-27\bin\java.exe' -cp out Node 1 8001 config\nodes.properties
& 'C:\Program Files\Java\jdk-27\bin\java.exe' -cp out Node 0 8000 config\nodes.properties
```

Stop a node with `Ctrl+C`. Do not start two nodes on the same port.

## Required API

| Method | Endpoint | Purpose |
| --- | --- | --- |
| POST | `/api/chat` | Receives a chat message, merges clocks, and stores it in logical order. |
| POST | `/api/token` | Transfers the token and high-score table around the ring. |
| POST | `/api/election` | Handles `ELECTION`, `OK`, and `COORDINATOR` messages. |
| GET | `/api/health` | Returns `{"status":"ALIVE"}` for liveness checks. |

Verify all configured peers after startup:

```powershell
.\scripts\Test-Network.ps1 -ConfigPath config\nodes.properties
```

The check exits with an error if any configured node is unreachable.

Send a test chat directly to Node 1 using the address configured for that laptop:

```powershell
Invoke-RestMethod -Method Post -ContentType 'application/json' `
  -Uri http://<NODE_1_WIFI_IP>:8001/api/chat `
  -Body '{"sender_id":0,"text":"Hello","lamport":1,"vector":[1,0,0,0,0,0,0,0,0,0]}'
```

The receiving terminal prints the ordered chat log and updated Lamport/vector clocks. See [SETUP_AND_TEST_GUIDE.md](docs/SETUP_AND_TEST_GUIDE.md) for the test procedure and [TEAM_CONTRIBUTIONS.md](docs/TEAM_CONTRIBUTIONS.md) for intentionally unclaimed team tasks.
