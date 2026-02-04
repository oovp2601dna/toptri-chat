# 🍽️ Toptri Chat

Toptri Chat is a simple **buyer–seller real-time menu selection application** built with **Spring Boot and Firebase Firestore**.

A buyer sends a food request (e.g. *nasi padang*), the seller receives it, selects available menu items, and the buyer can immediately purchase one of the options.

---

## ✨ Features

### 👤 Buyer
- Send food requests
- Receive up to 3 menu recommendations
- View menu price and vendor
- Purchase menu items (Buy)
- Orders are automatically stored in Firestore

### 🧑‍🍳 Seller
- Receive buyer requests in real-time
- View buyer messages
- Select up to 3 menu items
- Send menus to buyer with one click

### 🔥 Backend
- Spring Boot REST API
- Firebase Firestore (NoSQL)
- Transaction-safe (request claiming & buying)
- Simple polling-based real-time flow

---

## 🛠️ Tech Stack

- **Backend**: Java, Spring Boot 3
- **Database**: Firebase Firestore
- **Frontend**: HTML, CSS, Vanilla JavaScript
- **Build Tool**: Maven
- **Version Control**: Git & GitHub

---

## 📂 Project Structure
```text
toptri-chat/
│
├─ src/main/java/com/toptri/
│ └─ ToptriSimpleFirestoreApp.java
│
├─ src/main/resources/
│ ├─ static/
│ │ ├─ buyer.html
│ │ ├─ seller.html
│ │ ├─ buyer.js
│ │ ├─ seller.js
│ │ ├─ api.js
│ │ └─ styles.css
│ │
│ └─ application.properties
│
├─ .gitignore
├─ pom.xml
└─ README.md
```

---

## 🚀 How to Run

### 1️⃣ Clone Repository
```bash
git clone https://github.com/oovp2601dna/toptri-chat.git
cd toptri-chat
```
### 2️⃣ Firebase Configuration
```bash
Create a Firebase project
Enable Firestore
Download Service Account JSON
Place it here:
src/main/resources/firebase-service-account.json
⚠️ Do NOT upload this file to GitHub
Add to .gitignore:
firebase-service-account.json
target/
```
### 3️⃣ Configure application.properties
```bash
server.port=8081
firebase.serviceAccountPath=classpath:firebase-service-account.json
```
### 4️⃣ Run Backend
```bash
mvn spring-boot:run
```
## How to use it

#### Buyer 
```bash
Open:
http://localhost:8081/buyer.html
Enter a request (example: nasi padang)
Click Send
Wait for menu recommendations
Click Buy
```
#### Seller
```bash
Open:
http://localhost:8081/seller.html
Wait for incoming requests
Click menu items (max 3)
Menus are sent automatically to the buyer
```
