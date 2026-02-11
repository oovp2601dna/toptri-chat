# 🍽️ Toptri Chat (Desktop Version)

Toptri Chat is a simple **real-time buyer–seller food request application** built with **JavaFX Desktop GUI** and **Firebase Firestore**.

A buyer sends a food request (example: *nasi padang*), sellers receive the request instantly, offer matching menu items, and the buyer can view offers in a chat-style interface.

---

## ✨ Features

### 👤 Buyer Window
- Type and send food requests
- Requests are stored in Firestore
- Automatically receives seller menu offers in real-time
- Chat-style instant messaging interface
- Can simulate purchasing a menu item

---

### 🧑‍🍳 Seller Window (Multiple Sellers Supported)
- Real-time request inbox (all buyer requests appear)
- Seller can select a request and respond
- Offers up to 3 menu items per request
- Offers are filtered based on the request category  
  (example: *nasi padang* → only menus with category *nasi padang*)

---

### 🔥 Firestore Backend Integration
- Requests stored in `requests` collection
- Seller offers stored in `offers` subcollection
- Real-time updates using Firestore listeners
- Multi-seller support (Seller A, Seller B, etc.)

---

## 🛠️ Tech Stack

- **Language**: Java 21  
- **Desktop GUI**: JavaFX  
- **Database**: Firebase Firestore (NoSQL)  
- **Build Tool**: Maven  
- **Version Control**: Git & GitHub  

---

## 📂 Project Structure

```text
toptri-chat/
│
├─ src/main/java/com/toptri/
│   └─ ToptriSimpleFirestoreApp.java   # Backend Firestore API (unchanged)
│
├─ src/main/java/com/toptri/desktop/
│   ├─ ToptriDesktopLauncher.java      # Main Desktop Launcher
│   ├─ FirestoreService.java           # Firestore helper methods
│   ├─ BuyerWindow.java                # Buyer chat GUI
│   ├─ SellerWindow.java               # Seller dashboard GUI
│   └─ UiKit.java                      # UI components & styling
│
├─ src/main/resources/
│   ├─ application.properties
│   └─ firebase-service-account.json   # NOT uploaded (ignored)
│
├─ .gitignore
├─ pom.xml
└─ README.md
```

## 🚀 How to Run

### 1. Clone the project

```bash
git clone https://github.com/oovp2601dna/toptri-chat.git
cd toptri-chat

```

### 2. Add Firebase Service Account
```
Place your Firebase key here:
src/main/resources/firebase-service-account.json
```
### 3. Run the Desktop App
```
mvn javafx:run
```

## Usage
### Buyer Window
```
Type a request (example: nasi padang)
Click Send
Wait for seller offers
```
```
Seller Window
Select a request from the inbox
Click menu items to send offers (max 3)
```



