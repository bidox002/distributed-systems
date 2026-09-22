# CSC 4722 Setup and Test Guide

## 1. Use the required architecture

The project remains divided exactly as the brief requests:

- `Node.java`: starts one node and its HTTP server.
- `models`: message, Lamport/vector clocks, and scoreboard state.
- `sync`: token-ring mutual exclusion and Bully election.
- `api`: REST routing and the JDK HTTP client helper.

The project uses only JDK APIs (`HttpServer` and `HttpClient`). JDK 27 is fine because the build uses `--release 11`.

## 2. Compile from a clean output folder

Run this in PowerShell from the project folder:

```powershell
Remove-Item out -Recurse -Force -ErrorAction SilentlyContinue
$javaHome = 'C:\Program Files\Java\jdk-27\bin'
$sources = Get-ChildItem src\main\java -Recurse -Filter '*.java' | ForEach-Object FullName
& "$javaHome\javac.exe" --release 11 -encoding UTF-8 -d out $sources
```

Successful compilation creates `out\Node.class` and package folders below `out`.

## 3. Start the required ten-node configuration

Open ten PowerShell windows and use the matching node ID and port below. Start Node 9 first and Node 0 last.

| Node ID | Port |
| --- | --- |
| 0 | 8000 |
| 1 | 8001 |
| 2 | 8002 |
| 3 | 8003 |
| 4 | 8004 |
| 5 | 8005 |
| 6 | 8006 |
| 7 | 8007 |
| 8 | 8008 |
| 9 | 8009 |

```powershell
& 'C:\Program Files\Java\jdk-27\bin\java.exe' -cp out Node ID PORT
```

Node 0 begins with the token. Node 9 is the initial leader.

## 4. Verify server health before testing algorithms

For each port, request `GET /api/health`:

```powershell
8000..8009 | ForEach-Object { Invoke-RestMethod "http://localhost:$_/api/health" }
```

Every response must be `{"status":"ALIVE"}`. Capture this output in `logs\integration\health.txt` for the report.

## 5. Demonstrate logical clocks and chat ordering

Send a known message from Node 0 to Node 1:

```powershell
Invoke-RestMethod -Method Post -ContentType 'application/json' `
  -Uri http://localhost:8001/api/chat `
  -Body '{"sender_id":0,"text":"Clock test","lamport":1,"vector":[1,0,0,0,0,0,0,0,0,0]}'
```

Expected receive state on Node 1: Lamport is `2` and the vector is `[1,1,0,0,0,0,0,0,0,0]`. The Node 1 terminal prints its ordered chat log. Repeat with messages from more than one sender and capture that output. The primary ordering is Lamport time; sender ID deterministically breaks ties.

## 6. Demonstrate token-ring mutual exclusion

Use an integration test to call `requestCriticalSection(player, delta)` on several nodes. Each node must update the high-score table only after receiving the one circulating token. Capture the token-transfer terminal output and assert that all score-table replicas converge.

## 7. Demonstrate Bully election after a leader failure

1. Confirm each terminal reports Node 9 as the initial leader.
2. Stop the Node 9 window.
3. Wait at least six seconds for health probes and election requests.
4. Check the Node 0 and Node 4 terminal output.

With Nodes 0-8 available, both should report `leader_id: 8`. Preserve the terminal output showing the failed health probe, election, and coordinator announcement.

## 8. Test matrix and evidence

| ID | Test | Expected evidence |
| --- | --- | --- |
| T01 | Start ten nodes | All ports 8000-8009 listen. |
| T02 | Health | Every node returns `ALIVE`. |
| T03 | Lamport receive | Receiver applies `max(local, incoming) + 1`. |
| T04 | Vector receive | Receiver takes element-wise maxima, then increments its own entry. |
| T05 | Chat ordering | Local log is sorted by logical timestamp. |
| T06 | Token circulation | Token moves through each active node. |
| T07 | Concurrent scores | Only token arrival applies each queued update; replicas converge. |
| T08 | Normal election | Highest available node announces coordinator. |
| T09 | Leader crash | Health failure causes a new coordinator to be elected. |
| T10 | Recovery decision | Document the chosen behavior when a stopped node returns. |

Save command output and screenshots under `logs\clocks`, `logs\token`, `logs\election`, and `logs\integration`. Use the same test IDs in the report and in team-member contribution notes.

## 9. Remaining report deliverables

Prepare three UML sequence diagrams: chat receive/clock merge, token-ring score update, and Bully election. The report must also assign ownership of each module and include the matching test evidence.
