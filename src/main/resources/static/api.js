export async function getJSON(url){
  const r = await fetch(url);
  if(r.status === 204) return null;
  if(!r.ok) throw new Error(await r.text());
  return await r.json();
}

export async function postJSON(url, body){
  const r = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body)
  });

  // kalau backend return kosong
  const text = await r.text();
  if(!r.ok) throw new Error(text || `HTTP ${r.status}`);
  return text ? JSON.parse(text) : null;
}
