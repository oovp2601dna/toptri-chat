# 🏪 Indomaret Point — Toptri Chat (Desktop)

A real-time **Buyer–Seller** chat application built with **JavaFX** and **Firebase Firestore**, designed for Indomaret Point product ordering. Buyers send requests, AI instantly replies with product categories, and Sellers receive orders in real-time.

---

## ✨ Features

### 👤 Buyer Window
- Send food/product requests via chat
- **AI Auto-Reply** instantly greets the buyer with a welcome message + store review video
- Choose product category: 😋 Yummy Choice · 🥐 Bakery · ☕ Coffee
- Interactive menu widget per category:
  - **Yummy Choice**: Bapao (Rp8,000), Dimsum (Rp7,000), Sosis (Rp10,000)
  - **Bakery**: Plain Roll (Rp5,000), Sweet Roll (Rp6,000), Meat Roll (Rp8,000)
  - **Coffee**: Latte (Rp15,000), Americano (Rp12,000), Squash (Rp10,000)
- Delivery form (name, phone, address)
- **Leaflet/OpenStreetMap map** — shows nearest Indomaret locations based on buyer's address
  - Geocoding via Google Geocoding API (if key provided) or Nominatim OSM (fallback)
  - Indomaret search via Google Places API or Overpass API (fallback)
- Select an Indomaret → order is instantly confirmed
- Sidebar showing all past requests
- Toast notification when order is completed
- Supports multiple concurrent requests

### 🧑‍🍳 Seller Window
- Real-time inbox of all buyer requests (status: OPEN)
- Animated slide-in toast notification for new incoming requests
- Chat view with buyer / seller / AI message bubbles
- Auto qty badge showing parsed order items (e.g. 🛒 2× nasi · 3× es teh)
- Send offers via:
  - **Click a menu item** from the Firestore menu list
  - **Type manually** (name + price + vendor + contact)
  - **Add a new menu** to Firestore + send the offer at the same time
- Maximum 3 offers per request
- Multi-seller support (Seller A, Seller B, etc.)

### 🤖 AI Auto-Reply (Ollama)
- Uses local **Ollama** with model **llama3.2**
- Automatically greets buyer on first message
- Static fallback if Ollama is not running (app still works normally)
- Notifies seller when buyer selects a product category

### 🔥 Firestore Backend
| Collection | Contents |
|---|---|
| `requests` | All buyer requests (status, buyerId, text, etc.) |
| `requests/{id}/messages` | Chat messages from buyer, seller, and AI |
| `requests/{id}/offers` | Seller offers (multi-item lines + grandTotal) |
| `menus` | Seller menu items (name, price, vendor, category, rating) |

---

## 🛠️ Tech Stack

| | |
|---|---|
| **Language** | Java 21 |
| **GUI** | JavaFX |
| **Database** | Firebase Firestore (NoSQL, real-time) |
| **AI** | Ollama (local LLM, model: llama3.2) |
| **Maps** | Leaflet.js + OpenStreetMap / Google Maps |
| **Geocoding** | Google Geocoding API / Nominatim OSM |
| **Build** | Maven |

---

## 📂 Project Structure

```
toptri-chat/
│
├── src/main/java/com/toptri/desktop/
│   ├── ToptriDesktopLauncher.java   # Entry point & launcher window
│   ├── BuyerWindow.java             # Buyer UI & logic
│   ├── SellerWindow.java            # Seller UI & logic
│   ├── FirestoreService.java        # All Firestore operations
│   ├── AiAutoReply.java             # AI reply, geocoding & map logic
│   └── UiKit.java                   # Shared UI components & design tokens
│
├── src/main/resources/
│   ├── firebase-service-account.json  # ⚠️ Not uploaded (listed in .gitignore)
│   └── application.properties
│
├── pom.xml
└── README.md
```

---

## 🚀 How to Run

### 1. Clone the repository
```bash
git clone https://github.com/oovp2601dna/toptri-chat.git
cd toptri-chat
```

### 2. Add Firebase Service Account
Place your Firebase key at:
```
src/main/resources/firebase-service-account.json
```
Download from: **Firebase Console → Project Settings → Service Accounts → Generate new private key**

### 3. (Optional) Set up Ollama for AI
```bash
# Install Ollama: https://ollama.com
ollama pull llama3.2
ollama serve
```
> If Ollama is not running, the app still works with a static fallback greeting.

### 4. (Optional) Google Maps API Key
Set in `AiAutoReply.java`:
```java
public static String GOOGLE_API_KEY = "ISI_API_KEY_DISINI";
```
Without a key, the map still works using OpenStreetMap + Overpass API (free).

### 5. Run the app
```bash
mvn javafx:run
```

---

## 📖 How to Use

### Buyer
1. Click **Open Buyer** in the launcher
2. Type your order in the chat field → click **Send**
3. AI will greet you and show product category buttons
4. Select a category → pick a menu item
5. Fill in the delivery form (name, phone, address)
6. Choose the nearest Indomaret from the map → order confirmed ✅

### Seller
1. Click **Open Seller A** or **Open Seller B** in the launcher
2. Buyer requests appear automatically in the left inbox
3. Click a request → view the conversation in the center panel
4. Send an offer by clicking a menu item (right panel) or typing manually
5. Maximum 3 offers per request

---

## ⚙️ Configuration

| File | What to change |
|---|---|
| `AiAutoReply.java` | `GOOGLE_API_KEY`, `STORE_VIDEO_URL`, menu list, fallback Indomaret locations |
| `AiAutoReply.java` | `ollamaModel` (default: `llama3.2`) |
| `firebase-service-account.json` | Your Firebase project credentials |
