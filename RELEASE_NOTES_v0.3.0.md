# Hisa v0.3.0 Release Notes

**Baseline:** `v0.2.4` (published tag)
**Review date:** 2026-09-14
**Current app version:** `0.2.4` (version bump still pending)
**Release status:** Proposed; the working tree contains both committed post-tag work and uncommitted candidate work.

## What is new

### Marketplace editing

- Stall owners can open their own stall and edit its products and service data.
- Stall and product edits preserve stable NIP-15 `d` identifiers instead of creating accidental duplicates.
- Product and service edit payloads retain currency, shipping metadata, original identifiers, and other fields needed to publish a faithful replacement event.
- Same-second replacement events use deterministic ordering, preventing stale marketplace records from winning arbitrarily.
- Marketplace cards and detail screens now reflect the updated product and stall metadata.
- Create and edit flows are connected through the unified marketplace navigation.

### Donations, zaps, and sponsors

- The Donate screen now includes a ranked sponsor ticker based on validated NIP-57 zap receipts.
- Sponsor totals are aggregated per sender, with a minimum accumulated threshold and a display limit.
- Zap receipt validation checks the receipt event, embedded zap request, provider identity, recipient, LNURL, and BOLT-11 amount.
- The Donate screen now has a Mission Badges section backed by the developer's Nostr badge definitions and award events.
- Payment targets and sponsor data can be loaded from a local cache while fresh relay data is being collected.
- A refresh action is available for donation targets and sponsor data.
- The configured hidden badge award event is ignored as an event. Its pubkeys do not suppress valid awards from other event IDs.

### Sign-in and startup

- Authentication and startup flows received a substantial reliability update.
- Splash handling now coordinates startup state more carefully before entering the main application.
- External signer and authentication transitions are routed through the updated navigation and view-model flow.
- Nostr client changes improve relay selection and subscription behavior during startup and reconnects.

### Feed and shop resilience

- Feed and marketplace snapshots are preserved when a relay temporarily reaches EOSE without returning events.
- Refreshes no longer immediately turn a previously loaded feed or stall list into an empty result during a transient relay miss.
- Feed and stall scroll-position writes are debounced to reduce unnecessary state and disk work.
- Feed loading state now accounts for stall loading, avoiding premature empty states.

### Navigation and app identity

- The navigation drawer now links directly to the Hisa developer profile and the project's GitHub repository.
- The drawer displays the generated application version string.
- Support contact details and donation configuration were updated.

## Changes since `v0.2.4`

### Committed post-tag history

The branch contains 15 commits after `v0.2.4`, through `5033f19` on 2026-09-02. The committed diff is:

- 28 files changed
- 1,330 lines added
- 254 lines removed
- Net change: 1,076 lines

The committed work is concentrated in four areas:

1. Marketplace owner editing, stable identifiers, edit payload preservation, and navigation.
2. NIP-57 zap sponsor discovery, validation, aggregation, and Donate screen presentation.
3. Authentication, splash startup, Nostr relay behavior, and navigation updates.
4. Feed, shop, and marketplace correctness fixes, including the BOLT-11 parser test.

### Uncommitted candidate work

The current worktree contains additional work that is not part of the committed post-tag history:

- 13 application tracked files changed: 777 additions and 454 deletions.
- 3 new Kotlin files: `DonationCacheStore.kt`, `DonationTargetsRepository.kt`, and `RemoteRelayDirectory.kt`.
- 6 new `drawable-nodpi` payment-logo assets.
- Donation target caching, NIP-65 relay discovery, wider metadata relay coverage, and badge event filtering.
- Feed, stall, sponsor, and profile loading resilience improvements.

This candidate layer should be reviewed and committed separately before calling the release complete.

## Proposed atomic commits

These commit boundaries keep each change reviewable and reversible. They are ordered from user-facing features to supporting infrastructure.

### 1. `feat(marketplace): add owner stall and product editing`

Scope:

- `app/src/main/java/com/hisa/ui/components/StallCard.kt`
- `app/src/main/java/com/hisa/ui/screens/lists/StallDetailScreen.kt`
- `app/src/main/java/com/hisa/ui/navigation/AppNavGraph.kt`
- `app/src/main/java/com/hisa/ui/navigation/MarketplaceEditNavigation.kt`
- `app/src/main/java/com/hisa/ui/screens/create/CreateStallScreen.kt`
- `app/src/main/java/com/hisa/ui/screens/create/CreateUnifiedScreen.kt`
- `app/src/main/java/com/hisa/ui/screens/shop/ShopScreen.kt`

### 2. `fix(marketplace): preserve stable NIP-15 identifiers and edit metadata`

Scope:

- `app/src/main/java/com/hisa/data/model/Product.kt`
- `app/src/main/java/com/hisa/data/model/ServiceListing.kt`
- `app/src/main/java/com/hisa/data/nostr/NostrMarketplaceParser.kt`
- `app/src/main/java/com/hisa/data/repository/MarketplaceRepository.kt`
- `app/src/main/java/com/hisa/data/repository/ProductRepository.kt`
- `app/src/main/java/com/hisa/data/repository/ServiceEventParser.kt`
- `app/src/main/java/com/hisa/domain/service/CreateMarketplaceService.kt`
- `app/src/main/java/com/hisa/ui/screens/create/CreateServiceViewModel.kt`
- `app/src/main/java/com/hisa/ui/components/ProductCard.kt`

### 3. `feat(donate): add validated zap sponsors and Mission Badges`

Scope:

- `app/src/main/java/com/hisa/data/repository/ZapSponsorsRepository.kt`
- `app/src/main/java/com/hisa/ui/screens/donate/DonateScreen.kt`
- `app/src/main/java/com/hisa/util/Constants.kt`
- `app/src/test/java/com/hisa/data/repository/Bolt11AmountParserTest.kt`

### 4. `fix(auth): harden startup and external authentication flow`

Scope:

- `app/src/main/java/com/hisa/viewmodel/AuthViewModel.kt`
- `app/src/main/java/com/hisa/ui/screens/splash/SplashActivity.kt`
- `app/src/main/java/com/hisa/data/nostr/NostrClient.kt`

### 5. `fix(feed): preserve snapshots during transient relay misses`

Scope:

- `app/src/main/java/com/hisa/data/cache/FeedCacheStore.kt`
- `app/src/main/java/com/hisa/data/repository/FeedRepository.kt`
- `app/src/main/java/com/hisa/ui/screens/create/CreateServiceScreen.kt`
- `app/src/main/java/com/hisa/ui/screens/shop/ShopScreen.kt`

### 6. `feat(donate): cache targets and discover remote read relays`

Scope for the current uncommitted candidate:

- `app/src/main/java/com/hisa/data/cache/DonationCacheStore.kt`
- `app/src/main/java/com/hisa/data/repository/DonationTargetsRepository.kt`
- `app/src/main/java/com/hisa/data/repository/RemoteRelayDirectory.kt`
- `app/src/main/java/com/hisa/data/repository/ZapSponsorsRepository.kt`
- `app/src/main/java/com/hisa/ui/screens/donate/DonateScreen.kt`
- `app/src/main/java/com/hisa/data/nostr/NostrClient.kt`
- `app/src/main/java/com/hisa/data/repository/MetadataRepository.kt`
- `app/src/main/java/com/hisa/data/repository/ProfileRepository.kt`
- `app/src/main/java/com/hisa/util/Constants.kt`
- `app/src/main/res/drawable-nodpi/btc_logo.png`
- `app/src/main/res/drawable-nodpi/eth_logo.png`
- `app/src/main/res/drawable-nodpi/ltc_logo.png`
- `app/src/main/res/drawable-nodpi/sol_logo.png`
- `app/src/main/res/drawable-nodpi/xmr_logo.png`
- `app/src/main/res/drawable-nodpi/zec_logo.png`

### 7. `fix(ui): stabilize feed and stall loading states`

Scope for the current uncommitted candidate:

- `app/src/main/java/com/hisa/data/repository/FeedRepository.kt`
- `app/src/main/java/com/hisa/data/repository/MarketplaceRepository.kt`
- `app/src/main/java/com/hisa/ui/screens/feed/FeedTab.kt`
- `app/src/main/java/com/hisa/ui/screens/shop/StallsTab.kt`
- `app/src/main/java/com/hisa/viewmodel/StallsViewModel.kt`

### 8. `feat(navigation): expose app identity and project links`

Scope for the current uncommitted candidate:

- `app/build.gradle`
- `app/src/main/java/com/hisa/ui/screens/main/MainScreen.kt`
- `app/src/main/java/com/hisa/util/Constants.kt`

### 9. `docs(release): document v0.3.0 changes and commit plan`

Scope:

- `.gitignore`
- `RELEASE_NOTES_v0.3.0.md`

Shared files in commits 6-8 should be staged by cohesive hunk, not duplicated wholesale. In particular, `NostrClient.kt`, `Constants.kt`, `DonateScreen.kt`, and `ZapSponsorsRepository.kt` contain changes from more than one concern and need intentional partial staging.

## Technical summary

### Nostr and relay behavior

- Metadata subscriptions query a small relay quorum instead of relying on one selected relay.
- Discovered NIP-65 relays supplement configured relays without overwriting the user's relay preferences.
- Relay discovery uses event verification and caches normalized read-relay URLs per remote pubkey.
- EOSE settling windows reduce false empty snapshots when multiple relays respond at different times.

### Donation data model

- Payment targets, badge awards, and zap sponsors are serializable cache records.
- Badge filtering is keyed by the complete award event ID, not by recipient pubkey.
- Valid sponsor totals are calculated from deduplicated receipt IDs and grouped by zap-request sender.
- Sponsor ranking is sorted by total milli-satoshis and most recent zap time.

### Project size and verification baseline

- 151 Kotlin files under `app/src/main` and `app/src/test`.
- 27,736 Kotlin source lines.
- 198 files under `app/src/main` and `app/src/test`.
- 211 tracked files in the repository at review time.
- The latest recorded `installDebug` attempt was interrupted with exit code 130; a clean single build should be run after the atomic commits are staged.

## Release checklist

- [ ] Stage and review the atomic commit groups above.
- [ ] Add tests for event-level hidden badge filtering and cached donation snapshots.
- [ ] Bump `versionName` and `versionCode` from the current `0.2.4` values.
- [ ] Run `./gradlew test`.
- [ ] Run `./gradlew installDebug` once, with no parallel Gradle invocations.
- [ ] Tag the final release after the build and device verification succeed.
