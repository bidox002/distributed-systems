# Automated and network integration results

Run date: 2026-09-24

## Automated regression suite

Command: `scripts/Run-Tests.ps1`

Result: **PASS**. Production and test sources compiled for Java 11. The suite passed Lamport/vector clock merging, deterministic message ordering, configured token hand-off pacing, health and leader endpoints, chat receive, token acceptance and duplicate rejection, forwarding sequence progression and large-sequence JSON precision, dashboard-originated local clock ticks, a ten-node localhost token ring with concurrent score updates and scoreboard convergence, three-node election, leader failure, and higher-node restart recovery.

Evidence: [test-output.txt](../logs/tests/test-output.txt)

## Five-laptop network checklist

The saved network captures are stale relative to the current `config/nodes.properties`. The health capture checks Nodes 0 and 1 at `172.16.50.71`, while the current configuration uses `172.16.50.63`; the chat capture also targets `172.16.50.71`. The health capture checks Node 7 at `172.16.50.63`, while current configuration uses `172.16.50.71`. Current Nodes 6 and 7 also have different host addresses, although the documented allocation places both on Laptop D. Confirm the current Wi-Fi addresses, update and distribute one identical configuration, and rerun the network checks before claiming current availability. No full-cluster results are claimed from the old captures.

Evidence files: [health.txt](../logs/integration/health.txt), [chat-node0-to-node1.txt](../logs/integration/chat-node0-to-node1.txt)

| ID | Check | Result | Evidence / note |
| --- | --- | --- | --- |
| T01 | Start all ten nodes across five laptops | [ ] Unverified | Existing health evidence uses addresses that differ from current configuration. |
| T02 | Health check all ten nodes | [ ] Stale evidence | Rerun after confirming and distributing current node addresses. |
| T03 | Chat receive and Lamport clock across laptops | [ ] Stale evidence | The saved Node 0 to Node 1 request succeeded at an old destination address; receiver clock output was not captured. |
| T04 | Vector clock merge across laptops | [ ] Pending | Automated merge assertions passed locally; distributed receiver state was not captured. |
| T05 | Deterministic chat ordering across laptops | [ ] Pending | Automated ordering assertions passed locally; distributed log was not captured. |
| T06 | Token circulation across all ten nodes | [ ] Local PASS | Ten-node localhost ring completed successive hand-offs; the five-laptop ring remains unverified. |
| T07 | Concurrent score updates and replica convergence | [ ] Local PASS | Updates queued on two nodes converged to the same scoreboard across the ten-node localhost ring; multi-laptop evidence remains pending. |
| T08 | Normal Bully election across laptops | [ ] Pending | Three-node loopback election passed; full-cluster convergence was not verified. |
| T09 | Leader failure and recovery across laptops | [ ] Pending | Three-node loopback failure recovery passed; full-cluster recovery was not verified. |
| T10 | Restart Node 9 after Node 8 takes over | [ ] Pending | Three-node loopback higher-node restart passed; full-cluster behavior was not verified. |

## Remaining network evidence

- [ ] Confirm each laptop's current Wi-Fi IP and update `config/nodes.properties`; the two Laptop D node addresses must match the actual Laptop D IP.
- [ ] Copy the same configuration to every laptop, start Nodes 9 down to 0, and rerun `scripts/Test-Network.ps1`.
- [ ] Capture fresh clock, chat, token-ring, scoreboard convergence, election, and restart evidence under `logs/`.
