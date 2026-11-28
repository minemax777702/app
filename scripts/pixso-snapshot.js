// Executed by Pixso Remote MCP. CONFIG is prepended by the trusted CI client.
const api = pixso.variables;
const collection = await api.getVariableCollectionByIdAsync(CONFIG.collection);
if (!collection) throw Error("Missing theme collection");
const hex = v => {
  if (!v || !["r","g","b","a"].every(k => typeof v[k] === "number") || v.a !== 1) throw Error("Expected opaque RGB token");
  return [v.r,v.g,v.b].map(x=>Math.round(x*255).toString(16).padStart(2,"0")).join("").toUpperCase();
};
const tokens = {};
for (const id of collection.variableIds) {
  const v = await api.getVariableByIdAsync(id);
  if (!v.name.startsWith("Theme/")) continue;
  tokens[v.name] = {id,light:hex(v.valuesByMode[CONFIG.modes.light]),dark:hex(v.valuesByMode[CONFIG.modes.dark])};
}
const norm = v => typeof v === "number" ? Math.round(v*10000)/10000 : v;
const safe = v => {
  if (typeof v === "symbol" || v === undefined) return null;
  if (Array.isArray(v)) return v.map(safe);
  if (v && typeof v === "object") return Object.fromEntries(Object.keys(v).sort().map(k=>[k,safe(v[k])]));
  return norm(v);
};
const paint = a => Array.isArray(a) ? a.map(p=>p.boundVariables?.color?.id ? {type:p.type,variable:p.boundVariables.color.id,opacity:p.opacity,visible:p.visible} : safe(p)) : safe(a);
const hash = x => {
  const s=JSON.stringify(safe(x));let a=2166136261,b=0x9e3779b9;
  for(let i=0;i<s.length;i++){a=Math.imul(a^s.charCodeAt(i),16777619);b=Math.imul(b^s.charCodeAt(i),2246822519);}
  return (a>>>0).toString(16).padStart(8,"0")+(b>>>0).toString(16).padStart(8,"0");
};
const inspect = (n,root=false) => {
  const data={id:n.id,name:n.name,type:n.type,width:n.width,height:n.height,visible:n.visible,opacity:n.opacity,rotation:n.rotation,cornerRadius:n.cornerRadius,layoutMode:n.layoutMode,paddingLeft:n.paddingLeft,paddingRight:n.paddingRight,paddingTop:n.paddingTop,paddingBottom:n.paddingBottom,itemSpacing:n.itemSpacing,primaryAxisAlignItems:n.primaryAxisAlignItems,counterAxisAlignItems:n.counterAxisAlignItems,clipsContent:n.clipsContent,strokeWeight:n.strokeWeight,strokeAlign:n.strokeAlign,effects:n.effects,fills:paint(n.fills),strokes:paint(n.strokes),constraints:n.constraints};
  if(!root){data.x=n.x;data.y=n.y;}
  if(n.type==="TEXT") Object.assign(data,{characters:n.characters,fontName:n.fontName,fontSize:n.fontSize,lineHeight:n.lineHeight,letterSpacing:n.letterSpacing,textAlignHorizontal:n.textAlignHorizontal,textAlignVertical:n.textAlignVertical,textAutoResize:n.textAutoResize});
  if(n.type==="INSTANCE") data.properties=n.componentProperties;
  if(n.type==="VECTOR") data.vectorPaths=n.vectorPaths;
  data.children=(n.children??[]).map(x=>inspect(x));
  return data;
};
const targets={};
for(const t of CONFIG.targets){
  const n=await pixso.getNodeByIdAsync(t.frame_id);
  if(!n || n.type!=="FRAME") throw Error("Missing canonical target "+t.key);
  targets[t.key]={id:n.id,hash:hash(inspect(n,true))};
}
const components={};
for(const [key,id] of Object.entries(CONFIG.components)){
  const n=await pixso.getNodeByIdAsync(id);
  if(!n) throw Error("Missing shared component "+key);
  components[key]={id,hash:hash(inspect(n,true))};
}
const pages=[];
for(const p of pixso.root.children){
  await p.loadAsync?.();
  const frames=p.children.flatMap(n=>n.type==="SECTION"?n.children.filter(x=>x.type==="FRAME").map(x=>[n.id,x.id,x.name]):n.type==="FRAME"?[[p.id,n.id,n.name]]:[]);
  pages.push({id:p.id,name:p.name,frames:frames.sort((a,b)=>a[1].localeCompare(b[1]))});
}
return {schema:1,tokens,targets,components,pages};
