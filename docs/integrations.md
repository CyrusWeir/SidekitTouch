# Sidekit service integrations

## Trip Planning

The transit implementation uses the `transportnsw.info` service referenced by
`AndroidStudioProjects/392Fan/.../TransportService.kt`:

- Stop search: `GET https://transportnsw.info/api/trip/v1/stop-finder-request`.
- Departures: `GET https://transportnsw.info/api/trip/v1/departure-list-request`.
- Journeys: `POST https://transportnsw.info/api/graphql`, using the site's current
  `widgets.tripOptions(input: TripPlannerInput)` query.

These calls were verified without an Authorization header on 4 November 2026.
The live probe resolved Central and returned five journeys, 40 departures, and
three live departure estimates. `python3 scripts/verify_nsw_transit.py` repeats
the probe using the current Sydney date and time. Results vary by service time.

The [official NSW trip planner](https://transportnsw.info/trip-planner) publishes
the GraphQL query in its application assets. This website service is distinct
from the documented Open Data Hub API. Its schema and anonymous access can change;
there is no documented third-party availability guarantee for these website endpoints.
There is no claim that this is an official TripView developer API.

The [Open Data Hub user guide](https://opendata.prod.transport.nsw.gov.au/developers/userguide)
requires registration and an API token for the documented
`api.transport.nsw.gov.au/v1/tp/` APIs, with `Authorization: apikey <token>`.
That API is not the backend configured in Sidekit after the user's 392Fan instruction.
No token from 392Fan is copied into Sidekit.

The Plan screen has From and To stop searches, journey options, and departures.
The selected stop IDs survive a saved journey. Journey legs are stored separately
from personal notes. Saved times are snapshots; the departure screen has Refresh.
All input and display times use `Australia/Sydney`, including daylight saving.
A missing estimated departure is labelled Scheduled. Cancelled departures remain visible.
Local plans and existing saved places remain available. Maps source is unchanged.

## Mail

Mail's Account option configures one IMAP/SMTP account. Connect verifies both
servers before saving the configuration. IMAP uses implicit TLS, normally port 993.
SMTP uses implicit TLS, normally port 465, or required STARTTLS on port 587.
Certificate validation and hostname checks remain enabled in production.

Refresh inbox retrieves the latest 100 messages, with stable identity based on
account, IMAP UIDVALIDITY, and UID. The reader supports plain text and HTML MIME
alternatives, with HTML converted to text. Reply creates an editable draft.
SMTP acceptance moves a message from Outbox to Sent. Acceptance does not prove
recipient delivery. Failed or uncertain sends stay in Outbox for explicit retry.
Sidekit does not automatically retry an uncertain submission.

Account credentials and cached mail use Android Keystore AES-GCM encryption in
app-private, non-backup storage. The first successful migration preserves existing
`mail.bin` drafts in the encrypted store before removing the old plaintext file.
Unreadable data is preserved and reported. A cache swipe deletes the phone's copy;
it does not delete a message on the IMAP server.

Providers must permit IMAP and SMTP authentication with a password or app password.
OAuth-only policies require provider-specific OAuth registration, which is not
implemented. Mail attachments, server-side folder management, and background push
are outside this implementation. No personal mail account was supplied for live
verification. Local GreenMail tests perform actual TLS IMAP login, MIME inbox reads,
and SMTP delivery to a local test recipient. Another test rejects a certificate
whose hostname does not match and verifies that credentials were not saved.

[JavaMail's Android documentation](https://javaee.github.io/javamail/Android)
describes the Android-specific mail and activation libraries used here.

## Banking

The Banking options menu opens **Up · read-only** behind the existing Banking lock.
The Up screen reads account balances and transactions from
`https://api.up.com.au/api/v1`. Credentials and the last successful snapshot use
Android Keystore AES-GCM encryption in non-backup storage. Disconnect deletes both.

The bank's balance is authoritative. Imported transactions do not alter that balance
or the separate manual tracker. Pending transaction status remains visible.
Account pagination is followed; older transaction pages load on demand and deduplicate
by provider ID. Pagination URLs are restricted to Up's HTTPS API origin before a
bearer token is attached. A failed refresh preserves the previous snapshot and timestamp.

The [Up API reference](https://developer.up.com.au/) requires an existing Up customer
and a personal access token from `api.up.com.au` or Up's Data Sharing settings.
The [acceptable use policy](https://up.com.au/api-acceptable-use-policy/) limits the
API to personal use. The documented API has no payment or transfer initiation
endpoint. Sidekit's Up connector issues GET requests only. Manual tracker entries
remain local records; they do not move money.

No Up personal token was supplied, so personal bank access was not verified.
Broader Australian bank access requires an approved CDR arrangement and customer
consent, as described by the [Consumer Data Right provider requirements](https://www.cdr.gov.au/for-providers).
Payments require a separate payment provider, its provisioning, and authorization.

## Sidemessage MMS

Sidekit must hold Android's default SMS role, with SMS permissions, a valid default
SMS subscription, carrier MMS provisioning, and mobile data. Android's
[SmsManager MMS APIs](https://developer.android.com/reference/android/telephony/SmsManager)
select the carrier network and MMSC. Sidekit parses and persists message PDUs.

Incoming WAP notifications queue WorkManager downloads. Pending notification rows
are visible in conversations and support tap-to-retry. Successful retrieval replaces
the notification row, exposes text and image/audio/video parts, and posts a conversation
notification. Images appear in the conversation; attachments can open in a compatible
viewer through a narrow FileProvider grant. A notification response is submitted to
the carrier after retrieval. Failed downloads remain retryable.

Attach MMS uses Android's document picker. Send builds and persists an outgoing PDU,
checks the carrier's message size limit, and submits through SmsManager. Oversized
attachments require a smaller file; there is no silent compression. Carrier callbacks
set Sent or Failed. A failed outgoing MMS can be retried from its existing provider row.
The original SMS path and multipart SMS delivery reports remain intact.

The AOSP-derived PDU parser/composer/persister comes from
[`com.klinkerapps:android-smsmms`](https://github.com/klinker41/android-smsmms).
The library is archived; only its PDU support is used. Its broad file provider is
removed from the merged application manifest.

Tests cover binary notification/retrieval parsing, outgoing UTF-8 text and binary
attachment PDUs, pending/provider attachment rows, stream size limits, and FileProvider
scope. Real carrier download, acknowledgement, and sending remain unverified: they
require a provisioned physical SIM. The available emulator was owned by another
agent-device session, so this task did not take over or drive that session.

## Validation commands

```sh
python3 scripts/verify_nsw_transit.py
./gradlew :app:assembleDebug :app:testDebugUnitTest \
  --tests '*MailViewModelTest' --tests '*ImapSmtpAccountTest' \
  --tests '*BankingViewModelTest' --tests '*UpBankConnectorTest' \
  --tests '*SmsProviderTest' --tests '*SmsPlatformTest' --tests '*MmsPduTest' \
  --tests '*SidemessageViewModelTest' --tests '*TripRepositoryTest' \
  --tests '*NswTransitTest' --tests '*TripFlowTest'
```

Transit JSON fixtures are anonymous public API responses captured on 4 November 2026.
Test mail credentials exist only inside the local test server. No external account
credentials are stored in source, fixtures, or build configuration.

The final targeted run contains 48 passing tests. Full `:app:lintDebug` reports seven
existing errors in `launcher/ControlCentre.kt`, `apps/sideport/install/ApkInstaller.kt`,
and `MainActivity.kt`. None is in the integration changes; the unrelated source edits
were preserved.
