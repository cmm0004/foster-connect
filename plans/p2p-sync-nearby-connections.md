# Plan: Manual P2P Sync via Google Nearby Connections

## Context

Multiple household members fostering kittens need to share the same data without a backend server. We're adding manual peer-to-peer sync using Google Nearby Connections API. A "Sync" button on the foster list page lets users push a button on two devices to exchange and merge their databases. First sync handles pairing; subsequent syncs are one-tap.

**Merge strategy**: Record-level last-write-wins (using `updatedAtMillis`) for mutable entities, set-union (using `syncId` UUIDs) for append-only entities.

---

## Phase 1: Data Model Changes

**File: `app/src/main/java/com/example/fosterconnect/data/db/LifecycleEntities.kt`**

Add `syncId: String = ""` to these 5 auto-generated-PK entities (they have no stable cross-device identity):
- `CaseWeightEntity`
- `CaseStoolEntity`
- `CaseEventEntity`
- `CaseTreatmentEntity`
- `AssignedTraitEntity`

Add `updatedAtMillis: Long = 0L` to `CaseMedicationEntity` (currently has no modification timestamp, needed for LWW when `isActive` changes).

**File: `app/src/main/java/com/example/fosterconnect/data/db/AppDatabase.kt`**
- Bump version 14 -> 15 (destructive migration is already enabled)
- Register new `SyncDao`

**File: `app/src/main/java/com/example/fosterconnect/data/KittenRepository.kt`**
- Supply `syncId = UUID.randomUUID().toString()` at every insert call site for the 5 entities above (~8-10 call sites: `addWeight`, `addStool`, `addEvent`, `addTrait`, `scheduleNextTreatment`, `seedHistoricalFosters`)
- Supply `updatedAtMillis` when inserting/stopping medications

---

## Phase 2: New SyncDao

**New file: `app/src/main/java/com/example/fosterconnect/data/db/SyncDao.kt`**

Bulk-read suspend queries for full DB export (the existing DAOs return Flows, but sync needs one-shot reads):
- `getAllAnimals()`, `getAllFosterCases()`, `getAllWeights()`, `getAllStools()`, `getAllEvents()`, `getAllTreatments()`, `getAllMedications()`, `getAllMessages()`, `getAllCompletedRecords()`, `getAllTraits()`
- `getAllWeightSyncIds()`, `getAllStoolSyncIds()`, `getAllEventSyncIds()`, `getAllTreatmentSyncIds()` — for fast set-membership checks during merge
- `getTreatmentBySyncId(syncId)` — for treatment administered-state merge
- `insertWeightIfNew()`, `insertTraitIgnore()` — with `OnConflictStrategy.IGNORE`

---

## Phase 3: Sync Serialization

**New file: `app/src/main/java/com/example/fosterconnect/sync/SyncPayload.kt`**

Data class holding all entity lists + `deviceId` + `timestampMillis`. Uses `org.json` (already a dependency) for serialization:
- `toJson(): JSONObject` — each entity list becomes a JSON array of flat objects
- `fromJson(json: JSONObject): SyncPayload` — companion factory

Photos (`CasePhotoEntity`) excluded — URIs are device-local.

---

## Phase 4: Merge Algorithm

**New file: `app/src/main/java/com/example/fosterconnect/sync/SyncMerger.kt`**

`suspend fun merge(remote: SyncPayload, db: AppDatabase)` — runs in a Room transaction:

1. **Animals** (String PK): LWW by `updatedAtMillis`. Insert if missing, replace if remote is newer.
2. **FosterCases** (String PK): Same LWW. Processed after animals (FK dependency).
3. **Weights/Stools/Events** (append-only): Load local `syncId` set, insert remote records not in set (with `id = 0` for new auto-gen PK).
4. **Treatments** (append-only + mutable): Union by `syncId`. If both exist and remote has `administeredDateMillis` but local doesn't, update local.
5. **Medications** (String PK, mutable): Stopped-wins rule. If both active, LWW by `updatedAtMillis`.
6. **Messages** (String PK, immutable): Insert-ignore. Merge `isRead` (true wins).
7. **CompletedRecords** (String PK): Upsert (replace).
8. **Traits** (composite unique key): Insert-ignore (unique constraint deduplicates).

Returns `MergeStats` (counts of added/updated records).

---

## Phase 5: Nearby Connections Manager

**New file: `app/src/main/java/com/example/fosterconnect/sync/SyncState.kt`**

```
sealed class SyncState: Idle | Searching | Authenticating(endpointId, deviceName, authToken) |
                         Connected | Transferring | Merging | Done(MergeStats) | Error(message)
```

**New file: `app/src/main/java/com/example/fosterconnect/sync/NearbySyncManager.kt`**

- Strategy: `P2P_CLUSTER` (both devices advertise + discover simultaneously)
- Service ID: `"com.example.fosterconnect.sync"`
- Payload: `Payload.fromStream()` (JSON may exceed 32KB BYTES limit)
- Exposes `StateFlow<SyncState>` for UI observation
- Pairing stored in `SharedPreferences("sync_prefs")`: `device_id`, `paired_device_name`, `last_sync_millis`
- Auto-accepts connections from previously paired devices
- 30-second discovery timeout

**Lifecycle**:
1. Both devices call `startAdvertising()` + `startDiscovery()`
2. On discovery, the discoverer calls `requestConnection()`
3. Both receive `onConnectionInitiated` with 4-digit auth token
4. First time: show confirmation dialog. Paired device: auto-accept
5. On connected: both serialize DB and send via stream payload
6. On received: deserialize, run `SyncMerger.merge()`
7. Disconnect, show results

---

## Phase 6: UI Changes

**File: `app/src/main/res/layout/fragment_foster_list.xml`**
- Add sync FAB at bottom-left, mirroring the New Foster FAB:
  - `layout_gravity="bottom|start"`, `marginStart="16dp"`, `marginBottom="72dp"`
  - `backgroundTint="@color/clinical_sage"`, white tint
  - Need a sync icon drawable (create `ic_sync_24.xml` vector asset)

**New file: `app/src/main/res/drawable/ic_sync_24.xml`**
- Material sync icon (two curved arrows)

**File: `app/src/main/res/values/strings.xml`**
- Add: `sync_button`, `sync_searching`, `sync_confirm_title`, `sync_confirm_message`, `sync_transferring`, `sync_merging`, `sync_complete`, `sync_error`, `sync_stats_format`

**File: `app/src/main/java/com/example/fosterconnect/foster/FosterListFragment.kt`**
- Add `ActivityResultContracts.RequestMultiplePermissions` launcher for Bluetooth/WiFi/Location permissions (version-dependent)
- On sync button tap: check permissions, instantiate `NearbySyncManager`, call `startSync()`
- Collect `SyncState` flow, show dialogs: searching spinner -> auth confirmation -> transferring -> done toast

---

## Phase 7: Dependencies & Permissions

**File: `gradle/libs.versions.toml`**
```toml
playServicesNearby = "19.3.0"
play-services-nearby = { group = "com.google.android.gms", name = "play-services-nearby", version.ref = "playServicesNearby" }
```

**File: `app/build.gradle.kts`**
- Add `implementation(libs.play.services.nearby)`

**File: `app/src/main/AndroidManifest.xml`**
```xml
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" android:usesPermissionFlags="neverForLocation" />
```

---

## Implementation Order

1. `libs.versions.toml` + `build.gradle.kts` — add dependency, verify project syncs
2. `AndroidManifest.xml` — add permissions
3. `LifecycleEntities.kt` — add `syncId` and `updatedAtMillis` fields
4. `AppDatabase.kt` — bump version, register `SyncDao`
5. `SyncDao.kt` — create new DAO
6. `KittenRepository.kt` — supply `syncId` at all insert sites
7. `SyncPayload.kt` — serialization layer
8. `SyncMerger.kt` — merge algorithm
9. `SyncState.kt` — state sealed class
10. `NearbySyncManager.kt` — Nearby Connections lifecycle
11. `res/drawable/ic_sync_24.xml` — sync icon
12. `fragment_foster_list.xml` + `strings.xml` — UI elements
13. `FosterListFragment.kt` — wire up button, permissions, state observer

---

## Verification

- **Build**: `./gradlew assembleDebug` compiles cleanly
- **Unit test**: SyncPayload round-trip (serialize -> deserialize -> assert equality)
- **Unit test**: SyncMerger LWW (two payloads, same animal, different timestamps -> later wins)
- **Unit test**: SyncMerger union (two payloads, different weights -> both kept)
- **Unit test**: SyncMerger idempotency (merge same payload twice -> no duplicates)
- **Device test**: Install on two physical Android devices, create fosters on each, tap Sync on both, verify all data appears on both devices
- **Edge case**: Sync with identical data -> no changes, no duplicates
- **Edge case**: Deny permissions -> graceful error message
- **Edge case**: No peer found within 30s -> timeout message
