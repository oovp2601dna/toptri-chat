import { postJSON, getJSON } from "./api.js";

const statusEl = document.getElementById("status");
const reqIdEl = document.getElementById("reqId");
const reqTextEl = document.getElementById("reqText");
const menuList = document.getElementById("menuList");

let slots = ["", "", ""];
let currentRequestId = null;
let currentText = "";
let isBusy = false;

function setStatus(t){ statusEl.textContent = t; }

async function claimLatestIfAny(){
  if (isBusy) return;
  if (currentRequestId) return;

  isBusy = true;
  try{
    const data = await getJSON("/api/requests/latest");
    if(!data){
      setStatus("waiting...");
      return;
    }

    currentRequestId = data.requestId;
    currentText = data.text;

    reqIdEl.textContent = currentRequestId;
    reqTextEl.textContent = currentText;

    slots = ["", "", ""];
    menuList.innerHTML = "";
    setStatus("new request ✅ loading menus...");

    await loadMenus();
    setStatus("pick menu (max 3)");
  }catch(e){
    console.error(e);
    setStatus("error ❌");
  }finally{
    isBusy = false;
  }
}

async function loadMenus(){
  const cat = (currentText || "").trim().toLowerCase();
  const menus = await getJSON(`/api/menus?category=${encodeURIComponent(cat)}`);
  menuList.innerHTML = "";

  if(!menus || menus.length === 0){
    menuList.innerHTML = `<div class="small">No menus found for: <b>${cat}</b></div>`;
    return;
  }

  menus.forEach(m=>{
    const div = document.createElement("div");
    div.className = "menuItem";
    div.innerHTML = `<b>${m.name}</b><div class="small">Rp${m.price} • ${m.sellerId||""}</div>`;

    div.onclick = async () => {
      // anti double click
      if (div.dataset.locked === "1") return;
      div.dataset.locked = "1";

      try{
        await pickOne(m);
        // kasih tanda dipilih
        div.style.opacity = "0.5";
        div.style.pointerEvents = "none";
      }catch(err){
        div.dataset.locked = "0";
        alert("gagal kirim menu. cek console");
        console.error(err);
      }
    };

    menuList.appendChild(div);
  });
}

async function pickOne(m){
  if(!currentRequestId) return;

  const idx = slots.findIndex(x => !x);
  if(idx === -1){
    alert("udah 3 pilihan.");
    return;
  }
  if(slots.includes(m.name)){
    alert("menu itu udah kepilih");
    return;
  }

  slots[idx] = m.name;

  await postJSON("/api/seller/row", {
    requestId: currentRequestId,
    rowIndex: idx,
    content: m.name,
    vendor: (m.sellerId || "").trim(),
    price: Number(m.price || 0),
    score: 0
  });

  setStatus(`sent ✅ ${m.name} (${idx+1}/3)`);
}

setInterval(claimLatestIfAny, 1200);
