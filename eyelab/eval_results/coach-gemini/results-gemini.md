| Scenario | Policy | Outcome | Ground truth | Correct | Steps | Sim. time | Approvals | Caregiver | Re-records |
|---|---|---|---|---|---|---|---|---|---|
| clean | gemini | ready | works | yes | 9 | 52.5 s | 0 | 0 | 0 |
| weak_up | gemini | ready | works | yes | 11 | 53.7 s | 0 | 0 | 1 |
| turned_away | gemini | ready | works | yes | 10 | 58.5 s | 0 | 0 | 0 |
| too_far | gemini | ready | works | yes | 10 | 58.5 s | 0 | 0 | 0 |
| dark_room | gemini | ready | works | yes | 10 | 57.5 s | 0 | 1 | 0 |
| dim_room_unfixable | gemini | ready | works | yes | 10 | 52.5 s | 0 | 1 | 0 |
| very_dark_unfixable | gemini | needs_help | fails | yes | 3 | 32.9 s | 0 | 1 | 0 |
| no_face | gemini | ready | works | yes | 11 | 63.5 s | 0 | 1 | 0 |
| no_face_unfixable | gemini | needs_help | fails | yes | 4 | 38.9 s | 0 | 1 | 0 |
| strict_threshold | gemini | ready | works | yes | 11 | 70.1 s | 1 | 0 | 0 |
| strict_threshold_denied | gemini | needs_help | fails | yes | 14 | 104.3 s | 1 | 0 | 2 |

**11/11 correct.** Mean 9.4 steps and 58 s of simulated time per run.
