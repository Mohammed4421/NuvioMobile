Review records for Nuvio releases merged into Nuvio Plus. The sync workflow writes one per release
(`nuvio-<tag>.md`) on its review branch.

- `review-gate.py records` (the `nuvio-plus/review` status of every pull request) needs a record for
  the newest Nuvio release the commit brings in, with `status: approved` and every box ticked. The
  required record is worked out from Git, so deleting it does not skip it. A `rejected` record
  blocks while its release is part of the commit.
- `review-gate.py publish` (before every release) also asks GitHub that the commit came through a
  pull request merged by the owner, whose head passed `nuvio-plus/checks` and `nuvio-plus/review`,
  and that the record has not changed since. Text in the record is never taken as proof.
