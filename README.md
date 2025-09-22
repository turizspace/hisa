# Hisa - Local Community Task Marketplace

## Overview

Hisa is a decentralized local community task marketplace built on the Nostr protocol (NIP-99). It connects community members who need help with daily household tasks with capable individuals willing to provide these services.

## Why Hisa?

### Solving Real Community Needs
- Connects people needing help with those willing to provide it
- Assists seniors and busy families in finding trustworthy local help
- Creates flexible earning opportunities within communities
- Provides an alternative to expensive, impersonal service platforms

### Technical Innovation
- Leverages Nostr protocol for true decentralization
- No blockchain complexity required
- Privacy-preserving by design
- Low operational costs with no central servers
- Built on growing, active protocol

### Unique Advantages
- No platform fees (unlike TaskRabbit, Handy, etc.)
- Community-driven trust and reputation
- True data ownership and privacy
- Mobile-first for accessibility
- Simple, intuitive interface despite advanced technology

### Social Impact
- Strengthens local community bonds
- Creates flexible income opportunities
- Particularly helpful for seniors and disabled individuals
- Keeps economic activity within communities
- Builds trust between neighbors

### Sustainable Growth
- Natural community-by-community expansion
- Network effects within local groups
- Low infrastructure costs
- Scalable architecture for future features
- No dependency on central authorities

## Features

- **Decentralized**: Built on Nostr protocol ensuring privacy and data ownership
- **Community-Focused**: Designed for local community interaction
- **Task Management**: Post, browse, and manage household tasks
- **Secure Communication**: Direct messaging between task posters and service providers
- **Profile System**: Build reputation within the community
- **Mobile-First**: Native Android app for convenience

## Common Task Examples

### Home Maintenance
- Lawn mowing and garden maintenance
- Basic plumbing repairs (fixing leaky faucets)
- Light fixture installation
- Gutter cleaning
- Window washing
- Paint touch-ups

### Household Help
- House cleaning
- Laundry service
- Organization and decluttering
- Deep cleaning (spring cleaning)
- Window washing
- Carpet cleaning

### Seasonal Tasks
- Snow shoveling (winter)
- Leaf raking (fall)
- AC unit maintenance (summer)
- Holiday decoration setup/removal
- Pool opening/closing

### Pet Care
- Dog walking
- Pet sitting
- Litter box maintenance
- Basic grooming
- Pet feeding during vacations

### Senior Assistance
- Grocery shopping
- Medication pickup
- Light housekeeping
- Technology help
- Transportation to appointments

### Moving Help
- Packing/unpacking assistance
- Furniture assembly
- Heavy lifting
- Box disposal
- Storage organization

## Technical Implementation

### Nostr Protocol Integration (NIP-99)

#### Task Listing Structure
```json
{
  "kind": 30402,
  "content": "## House Cleaning Needed\nLooking for weekly house cleaning service.\n- 3 bedroom house\n- General cleaning and dusting\n- Bathroom and kitchen deep clean\n- 4 hours estimated\n\nPrefer morning hours. Must have own supplies.",
  "tags": [
    ["d", "house-cleaning-2025-08"],
    ["title", "Weekly House Cleaning Service Needed"],
    ["published_at", "1728277200"],
    ["t", "cleaning"],
    ["t", "household"],
    ["summary", "Weekly house cleaning for 3-bedroom home, 4 hours/week"],
    ["location", "Austin, TX"],
    ["price", "120", "USD", "week"],
    ["g", "9v6kw"],  // Geohash for location precision
    ["status", "active"]
  ]
}
```

#### Key Features Implementation
- **Task Categories**: Implemented using "t" tags for easy filtering
- **Location-Based Discovery**: Using geohash ("g" tag) for precise location matching
- **Price Structure**: Standardized pricing with currency and frequency
- **Task Status Tracking**: Using "status" tag ("active"/"completed")
- **Rich Media Support**: NIP-58 image tags for task photos
- **Task Updates**: Version control through event references

#### Integration Points
- Direct messaging (NIP-04) for task communications
- Profile metadata (NIP-01) for user profiles
- Image handling (NIP-58) for task photos
- Search and discovery via geohash and tags

### Security Features
- Encrypted communications
- Secure key storage
- Privacy-focused design

## Getting Started

### As a Task Poster
1. Create your Nostr account (or import existing keys)
2. Post a task with structured information:
   - Title and summary (clear, searchable)
   - Location (with optional geohash precision)
   - Category tags (using standardized categories)
   - Pricing (amount, currency, frequency if recurring)
   - Detailed markdown description
   - Task photos (using NIP-58 image tags)
3. Monitor and manage task status:
   - Track active/completed status
   - Update task details as needed
   - Maintain task history
4. Communicate securely:
   - Encrypted DMs with interested providers
   - Share additional details privately
5. Complete the process:
   - Update task status to completed
   - Optional: Create related recurring tasks

### As a Service Provider
1. Set up your profile with:
   - Skills and expertise
   - Availability
   - Service areas
2. Browse available tasks
3. Express interest in tasks
4. Communicate with task posters
5. Complete tasks and build reputation

## Local Community Benefits

- Strengthens community bonds
- Supports local economic activity
- Helps seniors and busy families
- Creates flexible earning opportunities
- Builds trust through reputation system

## Privacy and Trust

- Users control their own data
- No central authority
- Community-based reputation system
- Encrypted communications
- Optional identity verification

## Implementation Priorities

### Phase 1: Core Features
- NIP-99 compliant task creation and management
- Geohash-based local task discovery
- Standardized category system using tags
- Basic search and filtering
- Encrypted DM communication

### Phase 2: Enhanced Features
- Advanced search with multiple tag combinations
- Recurring task automation
- Task templates for common services
- Image carousel for task photos
- Location-based notifications

### Phase 3: Community Features
- Community reputation system
- Service provider verification
- Task history and statistics
- Category-specific task templates
- Local community groups

### Future Developments
- Payment integration with multiple currencies
- Multi-language support (using language tags)
- Advanced trust mechanisms
- Task scheduling and calendar integration
- Mobile push notifications
- Cross-platform support
- Accessibility features

## Challenges We're Addressing

- **User Onboarding**: Making Nostr technology invisible to end-users
- **Trust Building**: Developing robust community reputation systems
- **Local Adoption**: Strategies for community-by-community growth
- **User Education**: Clear guidelines for safe community interactions
- **Privacy Balance**: Maintaining privacy while building trust
- **Quality Assurance**: Community-driven service quality monitoring

## Contributing

Hisa is open to community contributions. Please see our contributing guidelines for more information. Android App Debug & Fix Log

This document summarizes the major errors encountered and resolved during the setup and debugging of the Hisa Android app, from initial project configuration to resolving advanced Jetpack Compose and navigation issues.

---

## 1. Project Initialization & Gradle Setup
- **Missing `namespace` in `build.gradle`**: Added the `namespace` property to the `android` block as required by AGP 8+.
- **Gradle Wrapper Issues**: Ensured the presence of `gradlew` and `gradlew.bat` scripts and the correct Gradle version in `gradle-wrapper.properties`.
- **Missing or Incorrect Repositories**: Updated `build.gradle` and `settings.gradle` to include `google()`, `mavenCentral()`, and `jcenter()` repositories.

## 2. Dependency Management
- **Missing Jetpack Compose Dependencies**: Added all required Compose libraries (`ui`, `material`, `material3`, `foundation`, `runtime`, etc.).
- **Missing WorkManager, OkHttp, Retrofit, Coil, and Serialization**: Added dependencies for background work, networking, and image loading.
- **Duplicate Dependencies**: Cleaned up duplicate and redundant dependencies in `app/build.gradle`.
- **JVM Target Compatibility**: Set `jvmTarget` to 17 in `kotlinOptions` and `compileOptions`.
- **Kotlin Compiler Extension Version**: Set `kotlinCompilerExtensionVersion` in `composeOptions`.

## 3. AndroidManifest & Resource Issues
- **Missing or Incorrect `package` Attribute**: Ensured the `package` attribute is only in `build.gradle` (`namespace`), not in the manifest.
- **Missing `exported` Attribute**: Added `exported` to activities as required by Android 12+.
- **Missing or Invalid Theme**: Fixed `themes.xml` to use a valid parent and added required color attributes.
- **Missing String Resources**: Added `app_name` and other required strings.
- **Missing Launcher Icons**: Added minimal icons to `mipmap-anydpi-v26`.

## 4. Jetpack Compose & UI Errors
- **Unresolved Compose References**: Added missing imports and dependencies for Compose UI, Material, Foundation, and Icons.
- **Experimental Material API Warnings**: Annotated usages or updated dependencies as needed.
- **Unresolved `padding`, `fillMaxWidth`, `Modifier`, etc.**: Ensured all Compose UI and Foundation imports and dependencies are present.
- **Unresolved `@Composable` Annotation**: Added missing imports and ensured functions are properly annotated.
- **Unresolved `collectAsState`**: Added `runtime-livedata` dependency and correct imports.
- **Unresolved `Icons`**: Added `material-icons-extended` dependency.

## 5. Navigation & ViewModel Issues
- **No Value Passed for Parameter `navController`**: Updated all usages of `MainScreen`, `FeedTab`, `MessagesTab`, and `ChannelsTab` to accept and receive `navController`.
- **No Value Passed for Parameter `userPubkey`**: Updated all relevant composables and navigation calls to pass `userPubkey`.
- **Missing `rememberNavController`**: Imported and used `rememberNavController` in `MainActivity` and navigation graph.
- **Navigation Graph Parameter Passing**: Updated `AppNavGraph` to pass `userPubkey` to `MainScreen`.

## 6. MaterialTheme and ColorScheme
- **Unresolved Reference: `colorScheme`**: Switched to `androidx.compose.material3.MaterialTheme` for `colorScheme` usage in `Profile.kt`.
- **Mixed Material2/Material3 Usage**: Standardized imports and usage to avoid conflicts.

## 7. Miscellaneous
- **Internal Property Access**: Changed `webSocket` in `NostrClient` to `internal` for ViewModel access.
- **ViewModel Factory Usage**: Ensured correct ViewModel instantiation with custom parameters.
- **General Import Fixes**: Added missing imports for all Compose, Navigation, and ViewModel features.

---

## Summary

This project required extensive debugging and configuration to:
- Align with modern Android and Jetpack Compose requirements
- Ensure all dependencies and resources are present
- Fix navigation and parameter passing
- Resolve all unresolved references and build errors

If you encounter new errors, check this log for solutions or ensure all dependencies and imports are up to date.

---

_Last updated: August 5, 2025_
