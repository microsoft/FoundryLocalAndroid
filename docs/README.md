<div align="center">
  <img alt="Foundry Local Android" src="../ipc-service/FoundryLocalApp/src/main/res/drawable/ic_launcher.png" height="100" style="max-width: 100%;">
  
  <h1>Foundry Local Android</h1>
  <h3>On-Device AI Inference for Android</h3>
  
  <p>
    <a href="#quick-start">Quick Start</a> •
    <a href="#installation">Installation</a> •
    <a href="#documentation">Documentation</a> •
    <a href="#support">Support</a>
  </p>
</div>

---

> **⚠️ Private Preview**: Foundry Local Android is currently in private preview and is **not recommended for production use**. APIs may change as we continue to improve it based on your feedback.

Foundry Local Android brings powerful generative AI directly to Android devices. Run AI models on-device for enhanced privacy, lower latency, and offline capability.

### Key Benefits

- **🔒 Privacy First**: All inference happens on-device - data never leaves the device
- **⚡ Low Latency**: No network round-trips for faster responses
- **💰 Cost Effective**: No cloud API costs
- **🌐 Offline Capable**: Works without internet once models are downloaded
- **🎯 Simple API**: Clean, intuitive interface

> **💻 To run AI models on Windows and macOS**: Visit the [main Foundry Local repo](https://github.com/microsoft/Foundry-Local)

---

### Architecture

Foundry Local Android consists of two components:

- **Foundry Local App** - provides the AI inference backend
- **Foundry Local SDK** - the library developers integrate to access Foundry Local App

The Foundry Local app serves as a Service App that supports client applications with AI features. Client apps connect to the Foundry Local app using the Foundry Local Android SDK to leverage its capabilities. One of the key advantages of the Foundry Local app operating independently of client apps is that it doesn't affect the size and performance of these apps. Additionally, it can potentially save storage through model sharing when applicable.

> **📐 Deep Dive**: See [SDK Architecture](SDK_ARCHITECTURE.md) for details on the dual-SDK design, the AIDL error handling constraint, and how the adapter layer bridges FLResult to exceptions.

### Features

- Download and load models with progress tracking
- Chat completions with streaming support
- Multi-turn conversations with conversation history
- Configurable inference parameters (temperature, top-k, top-p, etc.)

---

## 🚀 Quick Start

### 1. Download and Install Foundry Local App

Download and install the Foundry Local App from the [latest release](https://github.com/microsoft/FoundryLocalAndroid/releases) in this repository.

>Please use this version of Foundry Local App for your development and testing purposes. The public version available on the Google Play Store is intended solely for production usage. If you have any specific needs, please contact the team beforehand.

### 2. Run the Example App

Download and install the [Example Chat App](https://github.com/microsoft/FoundryLocalAndroid/releases) to see Foundry Local in action.

OR

Build it yourself: Want to build the example app from source? See the [examples/](examples/) directory and the [examples README](examples/README.md) for setup instructions.

---

## 👩‍💻 For Developers

### Quick Integration Guidance
- **[Integration Guide](INTEGRATION_GUIDE.md)** - Step-by-step setup and basic usage

### Detailed API Usage
- **[API Reference](API_REFERENCE.md)** - Complete API documentation with all classes and methods

### More Examples
- **[Examples](EXAMPLES.md)** - Complete code examples for common scenarios

### Best Practices and Troubleshooting
- **[Best Practices](BEST_PRACTICES.md)** - Development guidelines and recommendations
- **[Troubleshooting](TROUBLESHOOTING.md)** - Common issues and solutions

---

## 📄 License

Licensed under **Microsoft Software License Terms** - [View EULA](https://aka.ms/foundrylocal-androidEULA)

---

## 🆘 Support

### Get Help

- 📖 [Integration Guide](INTEGRATION_GUIDE.md) - Setup and usage
- 📖 [Troubleshooting](TROUBLESHOOTING.md) - Common issues
- 📧 Submit github issues
- 💬 [Submit feedback](https://aka.ms/foundrylocal-androidfeedback) - Share your experience

### Contributing

See [CONTRIBUTING.md](../CONTRIBUTING.md) for guidelines.

---

<div align="center">
  <p>Made by the Foundry Local Team at Microsoft</p>
</div>
