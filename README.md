# P2P Distributed Chat and Scoreboard

CSC 4722 course project implemented with Java 11 standard-library APIs only. Ten nodes run across five laptops on the same Wi-Fi or hotspot network.

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

1. Connect every laptop to the same Wi-Fi or hotspot. Guest networks that isolate devices will not work.
2. On each laptop, run `ipconfig` and record its Wi-Fi IPv4 address.
3. Replace the example addresses in [config/nodes.properties](config/nodes.properties) with the five current Wi-Fi IP addresses. Each laptop's address appears twice for its assigned nodes.
4. Copy or pull that identical completed configuration file onto every laptop.
5. In an elevated PowerShell terminal on each laptop, allow the two assigned ports through Windows Firewall:

```powershell
New-NetFirewallRule -DisplayName 'CSC 4722 Nodes' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8000,8001
```

Replace `8000,8001` with that laptop's assigned pair.

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

Send a test chat directly to Node 1 using the address configured for that laptop:

```powershell
Invoke-RestMethod -Method Post -ContentType 'application/json' `
  -Uri http://<NODE_1_WIFI_IP>:8001/api/chat `
  -Body '{"sender_id":0,"text":"Hello","lamport":1,"vector":[1,0,0,0,0,0,0,0,0,0]}'
```

The receiving terminal prints the ordered chat log and updated Lamport/vector clocks. See [SETUP_AND_TEST_GUIDE.md](docs/SETUP_AND_TEST_GUIDE.md) for the test procedure and [TEAM_CONTRIBUTIONS.md](docs/TEAM_CONTRIBUTIONS.md) for intentionally unclaimed team tasks.
