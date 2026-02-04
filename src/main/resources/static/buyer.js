import { postJSON, getJSON } from "./api.js";

const chat = document.getElementById("buyerChat");
const input = document.getElementById("buyerInput");
const sendBtn = document.getElementById("buyerSend");
const ridEl = document.getElementById("rid");

let currentRequestId = null;

// simpen state slot biar ga rerender dobel
const slots = [
  { content: null, vendor: "", price: 0, rowIndex: 0 },
  { content: null, vendor: "", price: 0, rowIndex: 1 },
  { content: null, vendor: "", price: 0, rowIndex: 2 },
];

function uuid(){
  return "req_" + Math.random().toString(16).slice(2) + "_" + Date.now();
}

function rupiah(n){
  const x = Number(n || 0);
  return "Rp" + x.toLocaleString("id-ID");
}

function render(){
  chat.innerHTML = "";

  // bubble request buyer
  const req = document.createElement("div");
  req.className = "bubble right";
  req.textContent = (input._lastSentText || "(request)");
  chat.appendChild(req);

  // 3 slot seller
  slots.forEach((s, i) => {
    const card = document.createElement("div");
    card.className = "bubble left";
    card.style.maxWidth = "100%";

    if(!s.content){
      card.classList.add("wait");
      card.textContent = `⏳ menunggu menu ${i+1}...`;
      chat.appendChild(card);
      return;
    }

    card.innerHTML = `
      <div style="font-weight:800; font-size:20px">${s.content}</div>
      <div class="small" style="margin-top:4px">${rupiah(s.price)} • ${s.vendor || "-"}</div>
      <button data-idx="${s.rowIndex}" style="margin-top:10px">Buy</button>
    `;
    const btn = card.querySelector("button");
    btn.onclick = () => onBuy(s.rowIndex);

    chat.appendChild(card);
  });
}

async function pollRows(){
  if(!currentRequestId) return;

  try{
    const data = await getJSON(`/api/buyer/rows?requestId=${encodeURIComponent(currentRequestId)}`);
    const rows = (data && data.rows) ? data.rows : [];

    // reset slot content dulu (biar update konsisten)
    for (let i=0;i<3;i++){
      slots[i].content = null;
      slots[i].vendor = "";
      slots[i].price = 0;
    }

    // map rows ke slot berdasarkan rowIndex
    rows.forEach(r => {
      const idx = Number(r.rowIndex);
      if(Number.isFinite(idx) && idx>=0 && idx<=2){
        slots[idx].content = r.content || r.menu || "";
        slots[idx].vendor = r.vendor || "";
        slots[idx].price = Number(r.price || 0);
      }
    });

    render();
  }catch(e){
    // kalau error, jangan bikin dobel2 juga
    console.error("pollRows error:", e);
  }
}

async function onBuy(rowIndex){
  if(!currentRequestId) return;

  const buyerName = prompt("Nama pembeli (optional):") || "";
  const buyerAddress = prompt("Alamat (optional):") || "";

  try{
    const res = await postJSON("/api/buyer/buy", {
      requestId: currentRequestId,
      rowIndex,
      buyerName,
      buyerAddress
    });

    alert(`✅ Buy sukses!\nOrder: ${res?.orderId || "(lihat Firestore orders)"}`);
  }catch(e){
    // tampilkan error detail
    alert(`❌ Buy gagal.\n${e?.message || "Cek console / backend log."}`);
    console.error("BUY error:", e);
  }
}

sendBtn.onclick = async () => {
  const text = input.value.trim();
  if(!text) return;

  currentRequestId = uuid();
  ridEl.textContent = currentRequestId;

  input._lastSentText = text; // biar render bubble requestnya
  input.value = "";

  // reset slot
  for (let i=0;i<3;i++){
    slots[i].content = null;
    slots[i].vendor = "";
    slots[i].price = 0;
  }

  render();

  await postJSON("/api/requests", { requestId: currentRequestId, text });
};

// polling
setInterval(pollRows, 1500);
