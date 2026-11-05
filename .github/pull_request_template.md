## What changed?

Describe the change and why it is needed. Link the issue, if any.

## How to test

List the steps to reproduce the original problem and verify the fix or feature.

1. 

## Real-device testing

App changes must be tested on a physical device before merge. An emulator alone does not satisfy this requirement. For changes that do not affect the app, explain why device testing does not apply and ask the maintainer to confirm.

- Device model:
- Android version:
- Keypad or attached module, if relevant:
- Commit tested:
- Test steps and results:
- Screenshots, recording, or relevant logs:

Remove secrets and personal data from all evidence.

## Checklist

Leave incomplete items unchecked. Explain any item that does not apply.

- [ ] I created this branch from the latest `main` and this PR targets `main`.
- [ ] I kept this PR focused and described its changes above.
- [ ] I built the affected app and recorded the build command and result below.
- [ ] I tested the changed behavior on a physical device and completed the device details above.
- [ ] I checked nearby behavior for regressions, including keypad navigation and input when affected.
- [ ] I ran the relevant automated tests and recorded commands and results below.
- [ ] I added or updated tests where practical, or explained why no test is needed.
- [ ] I updated documentation when behavior or setup changed.
- [ ] I removed debug code, credentials, and personal data from the diff and test evidence.

## Build and automated test results

Record exact commands, results, and any failures or tests not run.

## Known issues and exceptions

List limitations, remaining risks, and any checklist items that do not apply.

## Maintainer review

Changes to `main` require approval from @HarlenPostill. Request review only when the required testing is complete.
