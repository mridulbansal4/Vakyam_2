| Scenario | Policy | Outcome | Ground truth | Correct | Steps | Sim. time | Approvals | Caregiver | Re-records |
|---|---|---|---|---|---|---|---|---|---|
| clean | rules | ready | works | yes | 9 | 52.5 s | 0 | 0 | 0 |
| weak_up | rules | ready | works | yes | 11 | 53.7 s | 0 | 0 | 1 |
| turned_away | rules | ready | works | yes | 10 | 58.5 s | 0 | 0 | 0 |
| too_far | rules | ready | works | yes | 10 | 58.5 s | 0 | 0 | 0 |
| dark_room | rules | ready | works | yes | 10 | 57.5 s | 0 | 1 | 0 |
| dim_room_unfixable | rules | ready | works | yes | 10 | 52.5 s | 0 | 1 | 0 |
| very_dark_unfixable | rules | ready | works | yes | 18 | 75.4 s | 1 | 1 | 0 |
| no_face | rules | ready | works | yes | 10 | 57.5 s | 0 | 1 | 0 |
| no_face_unfixable | rules | needs_help | fails | yes | 3 | 32.9 s | 0 | 1 | 0 |
| strict_threshold | rules | ready | works | yes | 12 | 73.4 s | 1 | 0 | 0 |
| strict_threshold_denied | rules | needs_help | fails | yes | 10 | 66.9 s | 1 | 0 | 0 |

**11/11 correct.** Mean 10.3 steps and 58 s of simulated time per run.
