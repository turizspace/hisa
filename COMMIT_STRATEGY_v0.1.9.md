# v0.1.9 Uncommitted Changes - Recommended Commit Strategy

**Total Uncommitted:** 18 modified files + 6 new files  
**Lines:** +1,559 / -881  
**Recommended:** 6 logical atomic commits

---

## 📋 Commit Organization Strategy

Grouping changes by feature/domain ensures:
- Each commit is independently valuable
- Easier to review and test
- Can revert individual features if needed
- Clear git history for future maintenance

---

## 🎯 Recommended Commit Groups

### Commit 1: Core Cryptography Refactoring
**Message:** `feat(crypto): implement NIP-44 V2 encryption module with proper HKDF support`

**Files to stage:**
```
app/src/main/java/com/hisa/data/nostr/crypto/Nip44.kt (NEW)
app/src/main/java/com/hisa/data/nostr/crypto/Nip44V2.kt (NEW)
app/src/main/java/com/hisa/data/nostr/crypto/Hkdf.kt (NEW)
app/src/main/java/com/hisa/data/nostr/crypto/EncryptedPayload.kt (NEW)
app/src/main/java/com/hisa/data/repository/MessageRepository.kt (893 lines)
```

**Stats:** +~9,000 lines / -~847 lines  
**Why atomic:** Complete encryption system replacement, changing API

**Commit Description:**
```
feat(crypto): implement NIP-44 V2 encryption module

- Create new crypto module with proper HKDF-SHA256 key derivation
- Implement NIP-44 V2 spec-compliant encryption
- Extract HKDF utilities to reusable component
- Refactor MessageRepository to delegate to new crypto interface
- Significantly reduce encryption code complexity
- Better error handling and validation

New files:
- Nip44.kt: Public encryption interface
- Nip44V2.kt: Complete NIP-44 V2 implementation
- Hkdf.kt: RFC5869 HKDF-SHA256 key derivation
- EncryptedPayload.kt: Structured message format

This is a major internal refactoring that maintains API compatibility
while significantly improving code quality and spec compliance.
```

---

### Commit 2: Event Deduplication & Relay Improvements
**Message:** `feat(relay): add event deduplication with LRU cache and EOSE callback support`

**Files to stage:**
```
app/src/main/java/com/hisa/data/nostr/SubscriptionManager.kt (74 lines)
```

**Stats:** +74 insertions / -10 deletions  
**Why atomic:** Distinct feature addressing relay reliability

**Commit Description:**
```
feat(relay): add event deduplication and EOSE callbacks

- Implement LRU cache-based event deduplication (max 8,000 entries)
- Prevent duplicate EVENT deliveries from relays
- Add EOSE (End-Of-Stored-Events) callback support
- Split DM subscriptions into inbox + outbox filters
- Handle CLOSED and OK WebSocket notice types

Impact:
- Reduces relay bandwidth usage
- Eliminates duplicate message notifications  
- Better performance for large conversations
- Enables proper "loading complete" UI states

The deduplication system maintains a 8,000-entry LRU cache that
automatically evicts oldest entries when full.
```

---

### Commit 3: Messaging System Improvements
**Message:** `feat(messaging): rewrite messaging viewmodels with improved state management`

**Files to stage:**
```
app/src/main/java/com/hisa/viewmodel/MessagesViewModel.kt (919 lines)
app/src/main/java/com/hisa/viewmodel/ChannelsViewModel.kt (161 lines)
app/src/main/java/com/hisa/ui/screens/messages/MessagesTab.kt (48 lines)
app/src/main/java/com/hisa/ui/screens/messages/ConversationScreen.kt (38 lines)
app/src/main/java/com/hisa/viewmodel/ChannelDetailViewModel.kt (4 lines)
```

**Stats:** +1,170 insertions / -1,170 deletions  
**Why atomic:** Coordinated messaging layer improvements

**Commit Description:**
```
feat(messaging): rewrite messaging viewmodels with improved state management

- Major MessagesViewModel refactoring (919 lines changed)
  - Enhanced conversation sorting and filtering
  - Better EOSE (End-Of-Stored-Events) handling
  - Integrated event deduplication
  - Improved error handling and edge cases
  - Performance optimizations

- ChannelsViewModel improvements (161 lines changed)
  - Enhanced event handling logic
  - Integrated deduplication system
  - Better subscription management
  - Improved message sorting

- UI integration updates
  - MessagesTab: New EOSE termination logic
  - ConversationScreen: Improved rendering with loading states
  - ChannelDetailViewModel: Minor fixes

These changes work together to provide smooth, responsive messaging
with proper conversation state management.
```

---

### Commit 4: Loading State Components & UX
**Message:** `feat(ui): add TabLoading component and improve tab loading states`

**Files to stage:**
```
app/src/main/java/com/hisa/ui/components/TabLoading.kt (NEW - 4,734 bytes)
app/src/main/java/com/hisa/ui/screens/feed/FeedTab.kt (20 lines)
app/src/main/java/com/hisa/ui/screens/channels/ChannelsTab.kt (17 lines)
app/src/main/java/com/hisa/ui/screens/main/MainScreen.kt (25 lines)
```

**Stats:** +~4,800 insertions / -62 deletions  
**Why atomic:** Consistent loading experience across tabs

**Commit Description:**
```
feat(ui): add TabLoading component and improve loading states

- Create new TabLoadingPlaceholder composable component
- Add rememberTabLoadingVisibility() state hook
- Centralized loading UI pattern for all tabs
- Replace individual FeedSkeleton instances

Updated components:
- FeedTab: Use TabLoadingPlaceholder with EOSE callback
- ChannelsTab: Integrated loading states
- MainScreen: Navigation adjustments

Benefits:
- Consistent loading experience across all tabs
- Better performance through state management
- Smooth EOSE event transitions
- Simplified component implementation
```

---

### Commit 5: Self-Messaging Prevention Feature
**Message:** `feat(messaging): prevent self-messaging on service listings`

**Files to stage:**
```
app/src/main/java/com/hisa/ui/components/ServiceCard.kt (52 lines)
app/src/main/java/com/hisa/ui/components/FeedComponents.kt (34 lines)
app/src/main/java/com/hisa/data/model/ChatroomKey.kt (NEW - 2,390 bytes)
app/src/main/java/com/hisa/ui/screens/shop/ShopScreen.kt (3 lines)
app/build.gradle (4 lines - version bump)
```

**Stats:** +~2,480 insertions / -89 deletions  
**Why atomic:** Complete product feature (user-visible)

**Commit Description:**
```
feat(messaging): prevent self-messaging on service listings

- Add userPubkey parameter to ServiceCard
- Add userPubkey parameter to CompactServiceCard
- Conditionally hide message button on own listings
- Add support to SectionedFeed for userPubkey passing
- Create ChatroomKey model for conversation identification
- Propagate userPubkey through FeedTab and ShopScreen

User Experience:
- Users can no longer accidentally message themselves
- Own listings clearly marked (no message button)
- Cleaner, more intuitive interface

Technical:
- ChatroomKey model for future message deduplication
- Proper type-safe key handling
- Minimal performance impact

Version bump to 0.1.9 (versionCode 2).
```

---

### Commit 6: Infrastructure & Compatibility Enhancements
**Message:** `chore(infra): enhance security storage and external signer support`

**Files to stage:**
```
app/src/main/java/com/hisa/data/nostr/ExternalSignerManager.kt (96 lines)
app/src/main/java/com/hisa/data/storage/SecureStorage.kt (39 lines)
app/src/main/java/com/hisa/viewmodel/FeedViewModel.kt (11 lines)
build/reports/problems/problems-report.html (2 lines)
```

**Stats:** +148 insertions / -108 deletions  
**Why atomic:** Supporting infrastructure improvements

**Commit Description:**
```
chore(infra): enhance security and external signer support

- ExternalSignerManager improvements (96 lines)
  - Enhanced pubkey handling for external signers
  - Better key agreement support
  - Improved error logging for debugging
  - Support for various external signer types

- SecureStorage enhancements (39 lines)
  - Better encryption handling
  - Improved error messages
  - Better resource management
  - Enhanced key storage mechanisms

- FeedViewModel minor improvements (11 lines)
  - Code quality improvements
  - Better error handling

- Build output updates

These infrastructure changes improve reliability, security,
and support for hardware wallets and external signers.
```

---

## 📊 Summary Table

| # | Commit Message | Files | LOC Added | LOC Removed | Purpose |
|---|---|---|---|---|---|
| 1 | NIP-44 V2 crypto module | 5 | ~9,000 | ~847 | Core crypto refactoring |
| 2 | Event deduplication | 1 | 74 | 10 | Relay reliability |
| 3 | Messaging viewmodels | 5 | ~1,170 | ~1,170 | Message handling |
| 4 | TabLoading component | 4 | ~4,800 | ~62 | Loading UX |
| 5 | Self-messaging prevention | 4 updtd + 1 new + build | ~2,480 | ~89 | Product feature |
| 6 | Security & compatibility | 4 | 148 | 108 | Infrastructure |
| | **TOTAL** | **18+6** | **~17,672** | **~2,286** | **Complete v0.1.9** |

---

## 🔄 Implementation Steps

### Step 1: Stage and Commit Crypto Module
```bash
git add \
  app/src/main/java/com/hisa/data/nostr/crypto/Nip44.kt \
  app/src/main/java/com/hisa/data/nostr/crypto/Nip44V2.kt \
  app/src/main/java/com/hisa/data/nostr/crypto/Hkdf.kt \
  app/src/main/java/com/hisa/data/nostr/crypto/EncryptedPayload.kt \
  app/src/main/java/com/hisa/data/repository/MessageRepository.kt

git commit -m "feat(crypto): implement NIP-44 V2 encryption module

- Create new crypto module with proper HKDF-SHA256 key derivation
- Implement NIP-44 V2 spec-compliant encryption
- Extract HKDF utilities to reusable component
- Refactor MessageRepository to delegate to new crypto interface
- Significantly reduce encryption code complexity"
```

### Step 2: Stage and Commit Event Deduplication
```bash
git add app/src/main/java/com/hisa/data/nostr/SubscriptionManager.kt

git commit -m "feat(relay): add event deduplication with LRU cache and EOSE callback support

Impact:
- Reduces relay bandwidth usage
- Eliminates duplicate message notifications
- Better performance for large conversations
- Enables proper 'loading complete' UI states"
```

### Step 3: Stage and Commit Messaging Improvements
```bash
git add \
  app/src/main/java/com/hisa/viewmodel/MessagesViewModel.kt \
  app/src/main/java/com/hisa/viewmodel/ChannelsViewModel.kt \
  app/src/main/java/com/hisa/ui/screens/messages/MessagesTab.kt \
  app/src/main/java/com/hisa/ui/screens/messages/ConversationScreen.kt \
  app/src/main/java/com/hisa/viewmodel/ChannelDetailViewModel.kt

git commit -m "feat(messaging): rewrite messaging viewmodels with improved state management

- Major MessagesViewModel refactoring with better EOSE handling
- ChannelsViewModel improvements with deduplication integration
- Enhanced conversation sorting and filtering
- Performance optimizations"
```

### Step 4: Stage and Commit Loading Component
```bash
git add \
  app/src/main/java/com/hisa/ui/components/TabLoading.kt \
  app/src/main/java/com/hisa/ui/screens/feed/FeedTab.kt \
  app/src/main/java/com/hisa/ui/screens/channels/ChannelsTab.kt \
  app/src/main/java/com/hisa/ui/screens/main/MainScreen.kt

git commit -m "feat(ui): add TabLoading component and improve tab loading states

- Create new TabLoadingPlaceholder for consistent loading UI
- Integrate loading states across FeedTab, ChannelsTab, MainScreen
- Smooth EOSE event transitions"
```

### Step 5: Stage and Commit Self-Messaging Prevention
```bash
git add \
  app/src/main/java/com/hisa/ui/components/ServiceCard.kt \
  app/src/main/java/com/hisa/ui/components/FeedComponents.kt \
  app/src/main/java/com/hisa/data/model/ChatroomKey.kt \
  app/src/main/java/com/hisa/ui/screens/shop/ShopScreen.kt \
  app/build.gradle

git commit -m "feat(messaging): prevent self-messaging on service listings

- Add userPubkey parameter to ServiceCard and CompactServiceCard
- Hide message button on own listings
- Create ChatroomKey model for conversation identification
- Version bump to 0.1.9 (versionCode 3)"
```

### Step 6: Stage and Commit Infrastructure
```bash
git add \
  app/src/main/java/com/hisa/data/nostr/ExternalSignerManager.kt \
  app/src/main/java/com/hisa/data/storage/SecureStorage.kt \
  app/src/main/java/com/hisa/viewmodel/FeedViewModel.kt \
  build/reports/problems/problems-report.html

git commit -m "chore(infra): enhance security storage and external signer support

- ExternalSignerManager: Better key agreement and error logging
- SecureStorage: Enhanced encryption and error handling
- FeedViewModel: Code quality improvements"
```

---

## ✅ Benefits of This Organization

1. **Atomic Commits** - Each commit is independently useful
2. **Clear History** - Future developers see logical progression
3. **Selective Cherry-pick** - Can apply specific features to branches
4. **Easy Testing** - Test each feature independently
5. **Easier Debugging** - If issues arise, know exactly which commit
6. **Code Review** - Each commit has a clear purpose
7. **Release Flexibility** - Can include/exclude specific features if needed

---

## 🚀 After Committing

Once all 6 commits are made:

```bash
# Create release tag
git tag v0.1.9

# Verify tag
git tag -v v0.1.9
```

---

**Recommendation Level:** Strong - This grouping balances logical coherence with atomic commits
