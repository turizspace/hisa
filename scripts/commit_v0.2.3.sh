#!/usr/bin/env bash
set -euo pipefail

cd /home/turizspace/hisa

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "Not inside a git repository" >&2
  exit 1
fi

branch="$(git branch --show-current)"
if [[ -z "$branch" ]]; then
  echo "Unable to determine current branch" >&2
  exit 1
fi

echo "Working branch: $branch"

git checkout "$branch"

commit_group() {
  local message="$1"
  shift
  if [[ $# -eq 0 ]]; then
    echo "No files provided for commit: $message" >&2
    exit 1
  fi
  git add "$@"
  if git diff --cached --quiet; then
    echo "No staged changes for: $message"
    return 0
  fi
  git commit -m "$message"
}

# 1) Build/config baseline
commit_group "chore(build): prepare v0.2.3 config" \
  app/build.gradle \
  app/src/main/AndroidManifest.xml \
  app/src/main/res/xml/network_security_config.xml

# 2) Nostr signing, subscriptions, event handling
commit_group "feat(nostr): add signer and subscription flow" \
  app/src/main/java/com/hisa/data/nostr/ExternalSignerManager.kt \
  app/src/main/java/com/hisa/data/nostr/NostrClient.kt \
  app/src/main/java/com/hisa/data/nostr/NostrEventSigner.kt \
  app/src/main/java/com/hisa/data/nostr/SubscriptionManager.kt \
  app/src/main/java/com/hisa/data/nostr/EventVerifier.kt \
  app/src/main/java/com/hisa/data/nostr/blossom/BlossomClient.kt \
  app/src/main/java/com/hisa/data/nostr/crypto/EncryptedPayload.kt \
  app/src/main/java/com/hisa/util/RelayHealth.kt

# 3) Authentication and secure storage
commit_group "feat(auth): harden secure storage and auth" \
  app/src/main/java/com/hisa/data/storage/SecureStorage.kt \
  app/src/main/java/com/hisa/data/nostr/NostrSigningService.kt \
  app/src/main/java/com/hisa/util/AccountAuthUtils.kt \
  app/src/main/java/com/hisa/util/AuthPreferenceStore.kt \
  app/src/main/java/com/hisa/util/SecurePreferencesHelper.kt \
  app/src/main/java/com/hisa/util/CryptoUtils.kt \
  app/src/main/java/com/hisa/util/BudEventBuilder.kt \
  app/src/main/java/com/hisa/di/AppModule.kt \
  app/src/main/java/com/hisa/di/BlossomModule.kt \
  app/src/main/java/com/hisa/di/CreateServiceModule.kt \
  app/src/main/java/com/hisa/di/MessagesViewModelModule.kt

# 4) Repository and cache layer
commit_group "refactor(data): centralize repositories and caches" \
  app/src/main/java/com/hisa/data/cache/ProfileCache.kt \
  app/src/main/java/com/hisa/data/cache/FeedCacheStore.kt \
  app/src/main/java/com/hisa/data/cache/MessageCacheStore.kt \
  app/src/main/java/com/hisa/data/cache/StallCacheStore.kt \
  app/src/main/java/com/hisa/data/cache/UiResumeStateStore.kt \
  app/src/main/java/com/hisa/data/repository/ConversationRepository.kt \
  app/src/main/java/com/hisa/data/repository/FeedRepository.kt \
  app/src/main/java/com/hisa/data/repository/MarketplaceRepository.kt \
  app/src/main/java/com/hisa/data/repository/MessageRepository.kt \
  app/src/main/java/com/hisa/data/repository/OrderRepository.kt

# 5) Marketplace and content model updates
commit_group "feat(marketplace): extend stall and category models" \
  app/src/main/java/com/hisa/data/model/ServiceListing.kt \
  app/src/main/java/com/hisa/data/model/Stall.kt \
  app/src/main/java/com/hisa/util/CategoryUtils.kt \
  app/src/main/java/com/hisa/util/PriceUtils.kt \
  app/src/main/java/com/hisa/ui/components/CategoryRegistry.kt \
  app/src/main/java/com/hisa/ui/components/MarketplacePreviewCard.kt

# 6) Main app UI, navigation and screen behavior
commit_group "feat(ui): refresh main screens and navigation" \
  app/src/main/java/com/hisa/ui/navigation/AppNavGraph.kt \
  app/src/main/java/com/hisa/ui/screens/main/MainScreen.kt \
  app/src/main/java/com/hisa/ui/screens/feed/FeedTab.kt \
  app/src/main/java/com/hisa/ui/screens/messages/MessagesTab.kt \
  app/src/main/java/com/hisa/ui/screens/messages/ConversationScreen.kt \
  app/src/main/java/com/hisa/ui/screens/details/ServiceDetailScreen.kt \
  app/src/main/java/com/hisa/ui/screens/profile/Profile.kt \
  app/src/main/java/com/hisa/ui/screens/settings/Settings.kt \
  app/src/main/java/com/hisa/ui/screens/shop/StallsTab.kt \
  app/src/main/java/com/hisa/ui/screens/donate/DonateScreen.kt \
  app/src/main/java/com/hisa/ui/screens/faq/FAQScreen.kt

# 7) Create/upload and publishing flows
commit_group "feat(create): improve upload and create journeys" \
  app/src/main/java/com/hisa/ui/screens/create/CreateServiceViewModel.kt \
  app/src/main/java/com/hisa/ui/screens/create/CreateUnifiedScreen.kt \
  app/src/main/java/com/hisa/ui/screens/create/CreateStallScreen.kt \
  app/src/main/java/com/hisa/ui/screens/upload/UploadScreen.kt \
  app/src/main/java/com/hisa/ui/screens/upload/BlossomUploadScreen.kt

# 8) Viewmodels and regression tests
commit_group "test(viewmodel): add regression coverage" \
  app/src/main/java/com/hisa/viewmodel/AuthViewModel.kt \
  app/src/main/java/com/hisa/viewmodel/FeedViewModel.kt \
  app/src/main/java/com/hisa/viewmodel/MessagesViewModel.kt \
  app/src/main/java/com/hisa/viewmodel/ProfileViewModel.kt \
  app/src/main/java/com/hisa/viewmodel/ShopViewModel.kt \
  app/src/main/java/com/hisa/viewmodel/UploadViewModel.kt \
  app/src/main/java/com/hisa/viewmodel/OrderNotificationsViewModel.kt \
  app/src/test/java/com/hisa/util/AccountAuthUtilsTest.kt \
  app/src/test/java/com/hisa/util/RelayHealthTest.kt \
  app/src/test/java/com/hisa/data/nostr/NostrEventSignerTest.kt \
  app/src/test/java/com/hisa/data/nostr/SubscriptionRequestRelayTest.kt \
  app/src/test/java/com/hisa/viewmodel/FeedViewModelTest.kt \
  app/src/test/java/com/hisa/viewmodel/SubscriptionLifecycleStateMachineTest.kt \
  app/src/test/java/com/hisa/data/cache/UiResumeStateStoreTest.kt

# 9) Release notes and release cut
commit_group "chore(release): cut v0.2.3" \
  COMMIT_STRATEGY_v0.2.3.md \
  RELEASE_NOTES_v0.2.3.md

echo "All requested commits created."

git push origin "$branch"
