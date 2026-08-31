# IPC and embedded deployment modes

Foundry Local runs on-device inference either in the separately installed service app or inside your
application process. IPC and embedded modes use the same Kotlin API but differ in how your
application is packaged and operated.

Compare the installation experience, application-package impact, process isolation, model storage,
and runtime update behavior below.

## IPC mode

Your app includes `foundry-local-ipc-sdk-<version>.aar` and communicates with the Foundry Local
service app.

```text
Your app -> IPC AAR -> Foundry Local service app -> on-device inference
```

IPC mode characteristics:

- requires the Foundry Local service app from Google Play;
- runs the inference engine in the service process; and
- uses the service-managed runtime and a model cache isolated to the client app.

> **Good to know:** IPC mode depends on a compatible service-app installation. Handle
> disconnections and reacquire catalog, model, and client handles after reconnecting. Model files
> are not shared across client apps; orchestration maintains an isolated copy for each app.

## Embedded mode

Your app includes `foundry-local-embedded-sdk-<version>.aar`. The AAR bundles the inference runtime,
which loads inside your app process.

```text
Your app -> Embedded AAR -> in-process on-device inference
```

Embedded mode characteristics:

- requires no companion app;
- packages the inference runtime in your application; and
- uses your app process and app-managed model cache.

> **Good to know:** The embedded artifact increases application size and model memory competes
> directly with the rest of your app. A native runtime failure also terminates your app process.
> Measure the final APK or App Bundle produced from the release artifact rather than relying on a
> generic size estimate.

## What remains the same

Both modes expose the same shared API:

- `FoundryLocalManager.create(...)`
- `manager.getCatalog()`
- `catalog.listModels()` and `catalog.getModel(...)`
- `model.download()`, `load()`, `unload()`, and `removeFromCache()`
- `model.createChatClient()` and `model.createAudioClient()`
- suspend functions, Kotlin `Flow`, and `FoundryLocalException`

Application code for catalog access, model lifecycle, chat, and audio can therefore remain the same.
Initialization also uses the same `FoundryLocalManager.create(...)` call.

## Operational differences

| Concern | IPC mode | Embedded mode |
|---|---|---|
| Runtime process | Foundry Local service app | Your app |
| Companion app | Required | Not required |
| Runtime packaged in your app | No | Yes |
| Model cache ownership | Service-managed and isolated per client app | App-managed |
| Connection loss | Possible; reconnect | Not applicable |
| Native crash boundary | Service process | Your app process |
| Runtime updates | Delivered with the service app | Require shipping a new app build |

## Switching modes

Switching modes is more than renaming an AAR:

1. Replace the release AAR and rebuild the app.
2. Re-test model storage, memory use, startup behavior, and process recovery.
3. For IPC mode, verify service installation, compatibility, disconnection, and reconnection.
4. For embedded mode, verify native packaging and the final APK or App Bundle on every supported
   device configuration.

The shared API minimizes application-code changes, but deployment and operational behavior still
need mode-specific validation.

## Next steps

- [Integration Guide](INTEGRATION_GUIDE.md)
- [API Reference](API_REFERENCE.md)
- [Best Practices](BEST_PRACTICES.md)
- [Troubleshooting](TROUBLESHOOTING.md)
