const THEME_KEY="koetomo_theme",THEME_CYCLE=["dark","light","black"],THEME_ICON={dark:"",light:"",black:""};function applyTheme(theme){document.body.setAttribute("data-theme",theme),document.querySelectorAll(".theme-btn").forEach(btn=>{btn.classList.toggle("active",btn.dataset.theme===theme)});const tb=document.getElementById("themeToggleBtn");tb&&(tb.textContent=THEME_ICON[theme]||"",tb.title=`テーマ: ${theme}(タップで切替)`)}function initTheme(){applyTheme(localStorage.getItem(THEME_KEY)||"dark")}function setTheme(theme){resetCustomBackground(),localStorage.setItem(THEME_KEY,theme),applyTheme(theme)}function cycleTheme(){const cur=localStorage.getItem(THEME_KEY)||"dark";setTheme(THEME_CYCLE[(THEME_CYCLE.indexOf(cur)+1)%THEME_CYCLE.length])}initTheme();const ACCENT_KEY="koetomo_accent";function hexToRgbArr(hex){3===(hex=hex.replace("#","")).length&&(hex=hex.split("").map(c=>c+c).join(""));const num=parseInt(hex,16);return[num>>16&255,num>>8&255,255&num]}function rgbArrToHex(rgb){return"#"+rgb.map(v=>Math.max(0,Math.min(255,Math.round(v))).toString(16).padStart(2,"0")).join("")}function shade(rgb,percent){const target=percent<0?0:255,p=Math.abs(percent);return rgb.map(c=>c+(target-c)*p)}function applyAccentColor(hex){const rgb=hexToRgbArr(hex),root=document.documentElement.style,dark=rgbArrToHex(shade(rgb,-.22)),light=rgbArrToHex(shade(rgb,.35)),mid=rgbArrToHex(shade(rgb,.15));root.setProperty("--teal",hex),root.setProperty("--teal-dark",dark),root.setProperty("--teal-light",light),root.setProperty("--teal-rgb",rgb.join(",")),root.setProperty("--accent",hex),root.setProperty("--accent-hover",dark),root.setProperty("--accent-2",light),root.setProperty("--accent-gradient",`linear-gradient(135deg, ${hex} 0%, ${mid} 50%, ${light} 100%)`),document.querySelectorAll(".accent-swatch").forEach(btn=>{btn.classList.toggle("active",(btn.dataset.color||"").toLowerCase()===hex.toLowerCase())});const customInput=document.getElementById("accentCustomInput");customInput&&(customInput.value=hex)}function setAccentColor(hex){localStorage.setItem(ACCENT_KEY,hex),applyAccentColor(hex)}const BG_KEY="koetomo_custom_bg",BG_VARS=["--bg-content","--bg-rail","--bg-sidebar","--bg-input","--bg-hover"];function applyCustomBackground(hex){const rgb=hexToRgbArr(hex),root=document.documentElement.style;root.setProperty("--bg-content",hex),root.setProperty("--bg-rail",rgbArrToHex(shade(rgb,-.4))),root.setProperty("--bg-sidebar",rgbArrToHex(shade(rgb,-.15))),root.setProperty("--bg-input",rgbArrToHex(shade(rgb,.12))),root.setProperty("--bg-hover",rgbArrToHex(shade(rgb,.22)));const inp=document.getElementById("bgCustomInput");inp&&(inp.value=hex)}function setCustomBackground(hex){localStorage.setItem(BG_KEY,hex),applyCustomBackground(hex)}function resetCustomBackground(){localStorage.removeItem(BG_KEY),BG_VARS.forEach(v=>document.documentElement.style.removeProperty(v))}function initCustomBackground(){const saved=localStorage.getItem(BG_KEY);saved&&applyCustomBackground(saved)}function initAccent(){const saved=localStorage.getItem(ACCENT_KEY);saved&&applyAccentColor(saved)}function api(){return window.pywebview.api}const __koeInflight=new Map();
// 起動時に何度も呼ばれるが数秒は変わらない読み取りだけ、短時間キャッシュして重複通信を減らす。
const __koeCacheTTL={get_my_profile:4000,get_badges:4000,get_account_balance:4000,get_room_history:5000,get_activity_heatmap:5000,get_moderation_settings:8000,get_official_links:120000,get_community_categories:120000,get_my_communities:6000};
const __koeCache=new Map();
window.__koeCacheClear=function(){try{__koeCache.clear()}catch(e){}};

// ===== エラーメッセージの共通化 =====
// サーバー/ネイティブが返す生のエラーを、ユーザーに分かる日本語へ変換する。
// 該当しないものは「未知のエラー」として、報告をお願いする文言を出す。
function koeErrMsg(r,ctx){
  try{
    if(r==null) return "未知のエラーが発生しました（応答なし）。管理者に報告してください。";
    if(typeof r==="string"){ try{ r=JSON.parse(r); }catch(e){ return r; } }
    ctx=ctx||r.__method||"";
    var st=Number(r.status||0);
    var body=r.body&&typeof r.body==="object"?r.body:null;
    var srv=(r.message||(body&&(body.displayable_detail||body.detail||body.message||body.error))||"")+"";
    var raw=((r.error||"")+" "+(r.raw||"")+" "+srv).toLowerCase();
    if(r.session_expired||st===401) return "ログインの有効期限が切れました。もう一度ログインしてください。";
    if(/^(login|signup)/.test(ctx)){
      if(st===400||st===403||st===422||/パスワード|メール|認証|not found|invalid/.test(raw))
        return "メールアドレスかパスワードが違います。";
    }
    if(st<=0||/timeout|unable to resolve host|failed to connect|network|通信エラー|接続できません/.test(raw))
      return "通信できませんでした。電波の良い場所でもう一度お試しください。";
    if(st===429) return "操作が多すぎます。少し時間を置いてからお試しください。";
    if(st>=500) return "サーバーが混み合っています。時間を置いてからお試しください。";
    // サーバーが日本語で理由を返している場合はそのまま見せる（公式と同じ文言）
    if(srv&&/[ぁ-んァ-ヶ一-龠]/.test(srv)) return srv;
    if(st===404) return "対象が見つかりませんでした。削除されたか、すでに終了している可能性があります。";
    if(st===403) return "権限がないため実行できませんでした。";
    if(st===400) return "入力内容に問題があります。内容を確認してもう一度お試しください。";
    return "未知のエラーが発生しました（"+(ctx||"不明")+(st?" / status "+st:"")+"）。管理者に報告してください。";
  }catch(e){ return "未知のエラーが発生しました。管理者に報告してください。"; }
}

// 大画面(Meta Quest など)で本文列を画面中央に置くため、左側UI(レール+サイドバー)の実幅をCSSへ渡す
function koeSyncChromeWidth(){try{
  var c=document.querySelector('.main-screen > .content');
  if(!c){document.documentElement.style.removeProperty('--koe-chrome');return}
  var left=Math.round(c.getBoundingClientRect().left);
  document.documentElement.style.setProperty('--koe-chrome',left+'px');
}catch(e){}}
try{
  window.addEventListener('resize',function(){clearTimeout(window.__koeChromeT);window.__koeChromeT=setTimeout(koeSyncChromeWidth,120)});
  document.addEventListener('DOMContentLoaded',koeSyncChromeWidth);
  setTimeout(koeSyncChromeWidth,300);
  setTimeout(koeSyncChromeWidth,1500);
  if(window.ResizeObserver){var __ro=new ResizeObserver(function(){koeSyncChromeWidth()});setTimeout(function(){var c=document.querySelector('.main-screen > .content');if(c)__ro.observe(c)},400)}
}catch(e){}
async function callApi(methodName,...args){
// 読み取り専用(get_/resolve_/check_)の同一呼び出しが同時進行中なら、その1本に相乗りして重複通信を防ぐ
const isReadOnly=/^(get_|resolve_|check_|inspect_|is_)/.test(methodName);
let key=null;
if(isReadOnly){
try{key=methodName+"|"+JSON.stringify(args)}catch(e){key=null}
if(key&&__koeInflight.has(key))return __koeInflight.get(key);
}
// 短時間キャッシュのヒット判定
const ttl=__koeCacheTTL[methodName];
let ckey=null;
if(ttl){try{ckey=methodName+"|"+JSON.stringify(args)}catch(e){ckey=null}
if(ckey){const c=__koeCache.get(ckey);if(c&&(performance.now()-c.t)<ttl)return Promise.resolve(c.result);}}
const _t0=performance.now();
if(!isReadOnly){try{__koeCache.clear()}catch(e){}try{__koeInflight.clear()}catch(e){}} /* 書き込み後に古いキャッシュを返さない(プロフィール更新→再読込 など) */
const p=(async()=>{
const result=await window.pywebview.api[methodName](...args);
updateLatencyBadge(Math.round(performance.now()-_t0));
result&&result.session_expired&&handleSessionExpired();
try{ if(result&&typeof result==="object"&&result.ok===false){ result.__method=methodName; if(!result.friendly) result.friendly=koeErrMsg(result,methodName); } }catch(e){}
if(ckey&&result&&result.ok!==false){try{__koeCache.set(ckey,{t:performance.now(),result})}catch(e){}}
return result;
})();
if(key){
__koeInflight.set(key,p);
// 完了直後の同一呼び出し(起動時に複数の画面が同じ情報を要求する等)も1本にまとめる
const drop=()=>{setTimeout(()=>{if(__koeInflight.get(key)===p)__koeInflight.delete(key)},800)};
p.then(drop,()=>__koeInflight.delete(key));
}
return p;
}function updateLatencyBadge(ms){const el=document.getElementById("latencyBadge");el&&(el.textContent=ms+"ms",el.classList.remove("fast","mid","slow"),el.classList.add(ms<600?"fast":ms<1500?"mid":"slow"),el.title=`直近のAPI応答時間: ${ms}ms(タップでグラフ表示。緑<600ms / 橙<1500ms / 赤=遅い)`);try{if(!window.__koeLatencyHistory)window.__koeLatencyHistory=[];window.__koeLatencyHistory.push({t:Date.now(),ms:ms});if(window.__koeLatencyHistory.length>40)window.__koeLatencyHistory.shift();if(document.getElementById("latencyGraphModal")&&document.getElementById("latencyGraphModal").style.display!=="none")renderLatencyGraph();}catch(e){}}
function renderLatencyGraph(){try{
  var hist=window.__koeLatencyHistory||[];
  var svg=document.getElementById("latencyGraphSvg");
  var stats=document.getElementById("latencyGraphStats");
  if(!svg)return;
  if(!hist.length){svg.innerHTML="";if(stats)stats.textContent="まだ記録がありません";return;}
  var W=280,H=120,pad=8;
  var vals=hist.map(function(h){return h.ms});
  var max=Math.max.apply(null,vals),min=Math.min.apply(null,vals);
  var range=Math.max(1,max-min);
  var n=vals.length;
  var pts=vals.map(function(v,i){
    var x=n===1?W/2:pad+(W-2*pad)*(i/(n-1));
    var y=H-pad-(H-2*pad)*((v-min)/range);
    return x.toFixed(1)+","+y.toFixed(1);
  }).join(" ");
  var lastMs=vals[vals.length-1];
  var lastColor=lastMs<600?"#3FBF6B":lastMs<1500?"#E9B23C":"#F1436B";
  var avg=Math.round(vals.reduce(function(a,b){return a+b},0)/n);
  svg.innerHTML='<polyline points="'+pts+'" fill="none" stroke="'+lastColor+'" stroke-width="2.5" stroke-linejoin="round" stroke-linecap="round"/>'+
    '<line x1="'+pad+'" y1="'+(H-pad)+'" x2="'+(W-pad)+'" y2="'+(H-pad)+'" stroke="var(--border,#333)" stroke-width="1"/>';
  if(stats)stats.textContent="直近"+n+"件 / 平均"+avg+"ms / 最速"+min+"ms / 最遅"+max+"ms";
}catch(e){}}
function __koeAfterRelogin(){try{window.__koeCacheClear&&window.__koeCacheClear();}catch(e){}try{if(typeof reloadCurrentView==="function"){setTimeout(reloadCurrentView,150);}}catch(e){}}
async function handleSessionExpired(){if(window.__reloggingIn)return;if(document.getElementById("sessionExpiredModal"))return;var __ls=document.getElementById("loginScreen");if(__ls&&getComputedStyle(__ls).display!=="none")return;window.__reloggingIn=true;try{var cur=currentAccountId(),accs=getAccounts();var acc=accs.find(function(x){return x.user_id===cur})||accs[0];if(acc&&acc.token){var r=await window.pywebview.api.login_with_token(acc.token,String(acc.user_id));if(r&&r.ok){window.__reloggingIn=false;__koeAfterRelogin();try{toast(" セッションを自動で再確立しました");sfx("success")}catch(e){}return}}}catch(e){}try{if(window.__koeCredRelogin&&await window.__koeCredRelogin()){window.__reloggingIn=false;__koeAfterRelogin();try{toast(" 保存した認証情報で自動再ログインしました");sfx("success")}catch(e){}return}}catch(e){}window.__reloggingIn=false;showScreen("loginScreen");var le=document.getElementById("loginError");if(le){le.style.display="block";le.textContent="ログイン状態が切れました。下のアカウントをタップで再ログインできます。"}var accounts=getAccounts();if(accounts&&accounts.length>0)showSessionExpiredChooser(accounts)}function showSessionExpiredChooser(accounts){let m=document.getElementById("sessionExpiredModal");m&&m.remove(),m=document.createElement("div"),m.id="sessionExpiredModal",m.className="modal",m.style.display="flex",m.innerHTML='<div class="modal-content small">\n    <div class="modal-header"><span>ログインが切れました</span></div>\n    <div class="modal-body">\n      <p class="page-desc">別の端末でログインされたか、セッションが切れました。アカウントを選び直すか、ログイン画面へ進んでください。</p>\n      <div id="seAccounts" class="accounts-list" style="margin-top:8px;"></div>\n    </div>\n    <div class="modal-footer"><button id="seToLogin" class="btn-secondary">ログイン画面へ</button></div>\n  </div>',document.body.appendChild(m);const box=m.querySelector("#seAccounts");box.innerHTML=accounts.map(x=>`<div class="account-item">${avatarHtml(x.name,x.icon)}<div class="account-info"><div class="account-name">${escapeHtml(x.name||"user "+x.user_id)}</div><div class="account-id">ID: ${x.user_id}${x.method?' <span class="account-method">'+koeMethodLabel(x.method)+'</span>':""}</div></div>${x.method?koeMethodIcon(x.method):""}<button class="se-switch btn-primary" data-id="${x.user_id}" style="width:auto;">ログイン</button></div>`).join(""),box.querySelectorAll(".se-switch").forEach(b=>b.addEventListener("click",async()=>{const id=parseInt(b.dataset.id,10),acc=accounts.find(x=>x.user_id===id);if(!acc)return;b.disabled=!0,b.textContent="…";const r=await callApi("login_with_token",acc.token,String(acc.user_id));if(r&&r.ok){try{localStorage.setItem("koe_current_account",String(id))}catch(e){}try{sfx("success")}catch(e){}setTimeout(()=>location.reload(),400)}else b.disabled=!1,b.textContent="ログイン",toast("このアカウントもトークンが切れています","error")})),m.querySelector("#seToLogin").addEventListener("click",()=>{m.remove(),showScreen("loginScreen")})}function escapeHtml(s){return String(s==null?"":s).replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;").replace(/'/g,"&#39;")}function escAttr(s){return String(s==null?"":s).replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;").replace(/'/g,"&#39;")}initAccent(),initCustomBackground(),function(){try{const fs=localStorage.getItem("koe_fontsize");fs&&(document.documentElement.style.fontSize=fs+"%"),"off"===localStorage.getItem("koe_anim")&&document.body.classList.add("no-anim"),"on"===localStorage.getItem("koe_datasaver")&&document.body.classList.add("data-saver"),"compact"===localStorage.getItem("koe_density")&&document.body.classList.add("compact");try{applyFont()}catch(e){}}catch(e){}}();let __sfxCtx=null;function sfxCtx(){if(!__sfxCtx)try{__sfxCtx=new(window.AudioContext||window.webkitAudioContext)}catch(e){}return __sfxCtx}function sfxOn(){try{return"off"!==localStorage.getItem("koe_sound")}catch(e){return!0}}function sfxVol(){try{const v=parseFloat(localStorage.getItem("koe_sound_vol"));return isNaN(v)?.5:v}catch(e){return.5}}function __sfxTheme(){try{return localStorage.getItem("koe_sound_theme")||"default"}catch(e){return"default"}}function __sfxType(base){const t=__sfxTheme();return"retro"===t?"square":"soft"===t?"triangle":"pure"===t?"sine":base||"sine"}function __sfxThemeVol(){const t=__sfxTheme();return"soft"===t?.72:"retro"===t?.9:1}const __NOTE={C4:261.6,D4:293.7,E4:329.6,F4:349.2,G4:392,A4:440,B4:493.9,C5:523.3,D5:587.3,E5:659.3,F5:698.5,G5:784,A5:880,B5:987.8,C6:1046.5,E6:1318.5};function __tone(freq,opts){opts=opts||{};const ctx=sfxCtx();if(!ctx)return;const t0=ctx.currentTime+(opts.delay||0),dur=opts.dur||.15,osc=ctx.createOscillator(),g=ctx.createGain();osc.type=__sfxType(opts.type),osc.frequency.setValueAtTime(freq,t0),opts.glide&&osc.frequency.exponentialRampToValueAtTime(Math.max(1,opts.glide),t0+dur);const peak=(null==opts.vol?1:opts.vol)*sfxVol()*__sfxThemeVol()*.3,atk=null==opts.attack?.008:opts.attack;g.gain.setValueAtTime(1e-4,t0),g.gain.linearRampToValueAtTime(peak,t0+atk),g.gain.exponentialRampToValueAtTime(1e-4,t0+dur);let node=osc;if(opts.filter){const f=ctx.createBiquadFilter();f.type=opts.filterType||"lowpass",f.frequency.value=opts.filter,osc.connect(f),node=f}node.connect(g),g.connect(ctx.destination),osc.start(t0),osc.stop(t0+dur+.03)}function __noise(opts){opts=opts||{};const ctx=sfxCtx();if(!ctx)return;const t0=ctx.currentTime+(opts.delay||0),dur=opts.dur||.15,buf=ctx.createBuffer(1,Math.max(1,Math.ceil(ctx.sampleRate*dur)),ctx.sampleRate),d=buf.getChannelData(0);for(let i=0;i<d.length;i++)d[i]=2*Math.random()-1;const src=ctx.createBufferSource();src.buffer=buf;const f=ctx.createBiquadFilter();f.type=opts.filterType||"bandpass",f.frequency.setValueAtTime(opts.filter||1200,t0),opts.filterGlide&&f.frequency.exponentialRampToValueAtTime(opts.filterGlide,t0+dur),f.Q.value=opts.q||1;const g=ctx.createGain(),peak=(null==opts.vol?1:opts.vol)*sfxVol()*__sfxThemeVol()*.2;g.gain.setValueAtTime(1e-4,t0),g.gain.linearRampToValueAtTime(peak,t0+.01),g.gain.exponentialRampToValueAtTime(1e-4,t0+dur),src.connect(f),f.connect(g),g.connect(ctx.destination),src.start(t0),src.stop(t0+dur+.02)}function __arp(freqs,step,opts){(freqs||[]).forEach((f,i)=>__tone(f,Object.assign({},opts||{},{delay:(opts&&opts.delay||0)+i*step})))}function koeVibrate(ms){try{window.AndroidApi&&window.AndroidApi.vibrate&&window.AndroidApi.vibrate(ms||30)}catch(e){}}
function sfx(name){if(!sfxOn())return;try{const c=sfxCtx();c&&"suspended"===c.state&&c.resume()}catch(e){}const N=__NOTE;switch(name){case"like":__tone(N.E5,{dur:.1,vol:.9}),__tone(N.A5,{dur:.14,delay:.05,vol:.8}),__tone(N.C6,{dur:.16,delay:.1,vol:.45});break;case"unlike":__tone(N.A4,{dur:.1,vol:.5,glide:N.E4,type:"triangle"});break;case"post":__arp([N.C5,N.E5,N.G5,N.C6],.055,{dur:.16,vol:.65}),__tone(N.E6,{dur:.18,delay:.22,vol:.3});break;case"send":__noise({dur:.16,filter:500,filterGlide:2800,vol:.6}),__tone(N.A4,{dur:.1,delay:.02,vol:.35,glide:N.E5,type:"triangle"});break;case"message":__tone(N.G4,{dur:.09,vol:.5,glide:N.C5}),__tone(N.C5,{dur:.13,delay:.06,vol:.4});break;case"tab":__tone(240,{dur:.03,vol:.32,type:"square"});break;case"open":__tone(N.C5,{dur:.09,vol:.5,glide:N.G5,attack:.004});break;case"close":__tone(N.G5,{dur:.09,vol:.4,glide:N.C5});break;case"join":__arp([N.C5,N.E5,N.G5],.09,{dur:.2,vol:.65}),__tone(N.C6,{dur:.35,delay:.27,vol:.4});break;case"leave":__arp([N.G5,N.E5,N.C5],.09,{dur:.18,vol:.55});break;case"error":__tone(196,{dur:.16,vol:.6,type:"sawtooth",filter:900}),__tone(146,{dur:.24,delay:.09,vol:.5,type:"sawtooth",filter:800});break;case"notify":__tone(N.C6,{dur:.4,vol:.5}),__tone(1.5*N.C6,{dur:.35,delay:.004,vol:.16}),__tone(N.G5,{dur:.32,delay:.13,vol:.28});break;case"success":__arp([N.C5,N.E5,N.G5,N.C6],.07,{dur:.2,vol:.6}),__arp([N.G5,N.C6],.05,{dur:.3,delay:.3,vol:.4});break;case"bookmark":__tone(N.E5,{dur:.08,vol:.6}),__tone(N.B4,{dur:.11,delay:.05,vol:.5,glide:N.E5});break;case"follow":__tone(N.D5,{dur:.09,vol:.6}),__tone(N.A5,{dur:.14,delay:.06,vol:.6});break;case"refresh":__noise({dur:.24,filter:350,filterGlide:2e3,vol:.45});break;case"mute":__tone(320,{dur:.07,vol:.4,type:"square",glide:170});break;case"unmute":__tone(300,{dur:.07,vol:.4,type:"square",glide:560});break;case"toggle":__tone(440,{dur:.03,vol:.3,type:"square"});break;case"chime":__arp([N.C5,N.E5,N.G5],.07,{dur:.22,vol:.6}),__tone(N.C6,{dur:.3,delay:.21,vol:.35});break;case"ding":__tone(N.C6,{dur:.5,vol:.55}),__tone(2*N.C5,{dur:.45,delay:.003,vol:.2});break;case"bell":__tone(N.G5,{dur:.3,vol:.5}),__tone(N.C6,{dur:.5,delay:.08,vol:.5});break;case"coin":__tone(N.B5,{dur:.07,vol:.6}),__tone(2*N.E5,{dur:.3,delay:.06,vol:.55});break;case"gift":__arp([N.C5,N.G5,N.C6,2*N.E5],.06,{dur:.2,vol:.6}),__tone(2*N.G5,{dur:.35,delay:.26,vol:.3});break;case"sparkle":__arp([N.C6,2*N.E5,2*N.G5,3*N.C5],.04,{dur:.14,vol:.4,type:"sine"});break;case"pop":__noise({dur:.05,filter:1200,filterGlide:3e3,vol:.4}),__tone(N.C6,{dur:.06,vol:.3});break;case"boop":__tone(N.C4,{dur:.12,vol:.5,glide:N.G4,type:"triangle"});break;case"whoosh":__noise({dur:.3,filter:300,filterGlide:3500,vol:.45});break;case"levelup":__arp([N.C5,N.E5,N.G5,N.C6,2*N.E5],.07,{dur:.18,vol:.6}),__tone(2*N.G5,{dur:.4,delay:.36,vol:.35});break;case"achievement":__tone(N.C5,{dur:.12,vol:.5}),__tone(N.G5,{dur:.12,delay:.1,vol:.5}),__arp([N.C6,2*N.E5,2*N.G5],.06,{dur:.3,delay:.22,vol:.5});break;case"select":__tone(N.E5,{dur:.04,vol:.35,type:"sine"});break;case"cancel":__tone(N.E5,{dur:.1,vol:.4,glide:N.C5,type:"triangle"});break;case"warning":__tone(N.A4,{dur:.16,vol:.5,type:"square"}),__tone(N.F4,{dur:.16,delay:.14,vol:.5,type:"square"});break;case"heart":__tone(N.E5,{dur:.09,vol:.6}),__tone(N.A5,{dur:.12,delay:.07,vol:.55}),__tone(2*N.C5,{dur:.18,delay:.14,vol:.4});break;case"kick":__tone(N.A4,{dur:.18,vol:.5,glide:N.C4,type:"sawtooth"}),__noise({dur:.12,filter:800,filterGlide:200,vol:.3});break;case"alert":__tone(N.A5,{dur:.12,vol:.55}),__tone(N.A5,{dur:.12,delay:.16,vol:.55});break;case"msg_in":__tone(N.G5,{dur:.1,vol:.5}),__tone(N.C6,{dur:.16,delay:.08,vol:.45});break;case"swoosh_up":__noise({dur:.25,filter:400,filterGlide:4e3,vol:.4}),__tone(N.C5,{dur:.2,vol:.3,glide:N.C6,type:"sine"});break;default:__tone(600,{dur:.07,vol:.5})}}function fmtNum(n){return(n=Number(n)||0)>=1e4?(n/1e4).toFixed(n>=1e5?0:1).replace(/\.0$/,"")+"万":n>=1e3?n.toLocaleString("ja-JP"):String(n)}function getFilterWords(){try{return(localStorage.getItem("koe_ngwords")||"").split("\n").map(w=>w.trim()).filter(Boolean)}catch(e){return[]}}function getMutedUsers(){try{return JSON.parse(localStorage.getItem("koe_muted_users")||"[]")}catch(e){return[]}}function setMutedUsers(a){try{localStorage.setItem("koe_muted_users",JSON.stringify(a))}catch(e){}}function isMutedUser(uid){return-1!==getMutedUsers().indexOf(Number(uid))}function toggleMuteUser(uid){uid=Number(uid);let a=getMutedUsers();return-1!==a.indexOf(uid)?a=a.filter(x=>x!==uid):a.push(uid),setMutedUsers(a),-1!==a.indexOf(uid)}function isFilteredPost(p){try{var __mf=window.__tlMedia;if(__mf==="image"&&!p.image_url)return!0;if(__mf==="voice"&&!p.voice_url)return!0;if(__mf==="explicit"&&!p.is_explicit)return!0;if(isMutedUser(p.user_id))return!0;const words=getFilterWords();if(words.length){const t=((p.text||"")+" "+(p.name||"")).toLowerCase();if(words.some(w=>t.includes(w.toLowerCase())))return!0}}catch(e){}return!1}const KOE_FONTS={system:{family:"",url:null},gothic:{family:"'Noto Sans JP', sans-serif",url:"Noto+Sans+JP:wght@400;700"},round:{family:"'M PLUS Rounded 1c', sans-serif",url:"M+PLUS+Rounded+1c:wght@400;700"},mincho:{family:"'Noto Serif JP', serif",url:"Noto+Serif+JP:wght@400;700"},dot:{family:"'DotGothic16', sans-serif",url:"DotGothic16"},hand:{family:"'Yusei Magic', sans-serif",url:"Yusei+Magic"},pop:{family:"'Mochiy Pop One', sans-serif",url:"Mochiy+Pop+One"},serifpop:{family:"'Reggae One', serif",url:"Reggae+One"},maru:{family:"'Zen Maru Gothic', sans-serif",url:"Zen+Maru+Gothic:wght@400;700"},kosugi:{family:"'Kosugi Maru', sans-serif",url:"Kosugi+Maru"},mochi:{family:"'Mochiy Pop One', sans-serif",url:"Mochiy+Pop+One"},yomogi:{family:"'Yomogi', cursive",url:"Yomogi"},hachi:{family:"'Hachi Maru Pop', cursive",url:"Hachi+Maru+Pop"},kurenaido:{family:"'Zen Kurenaido', sans-serif",url:"Zen+Kurenaido"},klee:{family:"'Klee One', cursive",url:"Klee+One:wght@400;600"},yusei:{family:"'Yusei Magic', sans-serif",url:"Yusei+Magic"},kaisei:{family:"'Kaisei Decol', serif",url:"Kaisei+Decol:wght@400;700"},shippori:{family:"'Shippori Mincho', serif",url:"Shippori+Mincho:wght@400;700"},delagothic:{family:"'Dela Gothic One', sans-serif",url:"Dela+Gothic+One"},rocknroll:{family:"'RocknRoll One', sans-serif",url:"RocknRoll+One"},yuji:{family:"'Yuji Syuku', serif",url:"Yuji+Syuku"},kiwi:{family:"'Kiwi Maru', serif",url:"Kiwi+Maru:wght@400;500"},zenkaku:{family:"'Zen Kaku Gothic New', sans-serif",url:"Zen+Kaku+Gothic+New:wght@400;700"},zenold:{family:"'Zen Old Mincho', serif",url:"Zen+Old+Mincho:wght@400;700"},zenantique:{family:"'Zen Antique', serif",url:"Zen+Antique"},biz:{family:"'BIZ UDPGothic', sans-serif",url:"BIZ+UDPGothic:wght@400;700"},murecho:{family:"'Murecho', sans-serif",url:"Murecho:wght@400;700"},hina:{family:"'Hina Mincho', serif",url:"Hina+Mincho"},newtegomin:{family:"'New Tegomin', serif",url:"New+Tegomin"},stick:{family:"'Stick', sans-serif",url:"Stick"},rampart:{family:"'Rampart One', cursive",url:"Rampart+One"},train:{family:"'Train One', cursive",url:"Train+One"},chokokutai:{family:"'Chokokutai', cursive",url:"Chokokutai"}};function loadGFont(spec){if(!spec)return;const id="koeGF-"+spec.replace(/[^a-z0-9]/gi,"");if(document.getElementById(id))return;const l=document.createElement("link");l.id=id,l.rel="stylesheet",l.href="https://fonts.googleapis.com/css2?family="+spec+"&display=swap",document.head.appendChild(l)}function applyFont(){let key="system",custom="";try{key=localStorage.getItem("koe_font")||"system",custom=localStorage.getItem("koe_font_custom")||""}catch(e){}if("custom"===key&&custom.trim())return loadGFont(custom.trim().replace(/\s+/g,"+")),void(document.body.style.fontFamily="'"+custom.trim()+"', sans-serif");const f=KOE_FONTS[key]||KOE_FONTS.system;f.url&&loadGFont(f.url),document.body.style.fontFamily=f.family||""}const __loadedScripts={};function loadScript(src){return __loadedScripts[src]||(__loadedScripts[src]=new Promise((resolve,reject)=>{const el=document.createElement("script");el.src=src,el.onload=()=>resolve(),el.onerror=()=>{delete __loadedScripts[src],reject(new Error("load failed: "+src))},document.head.appendChild(el)})),__loadedScripts[src]}let __likedCache=null;function getLikedSet(){if(__likedCache)return __likedCache;try{__likedCache=new Set(JSON.parse(localStorage.getItem("koe_liked_posts")||"[]").map(String))}catch(e){__likedCache=new Set}return __likedCache}function markLiked(id,liked){const set=getLikedSet();liked?set.add(String(id)):set.delete(String(id));try{var arr=[...set];if(arr.length>3000)arr=arr.slice(arr.length-3000);__likedCache=new Set(arr);localStorage.setItem("koe_liked_posts",JSON.stringify(arr))}catch(e){}}function postLiked(p){try{return!!p.liked||getLikedSet().has(String(p.id))}catch(e){return!!p.liked}}function koeMethodIcon(m){
  var wrap=function(bg,inner){return '<span class="acct-svc" title="'+koeMethodLabel(m)+'" style="background:'+bg+'">'+inner+'</span>';};
  switch(m){
    case"x": return wrap("#000",'<svg viewBox="0 0 24 24" width="12" height="12" fill="#fff"><path d="M18.244 2.25h3.308l-7.227 8.26 8.502 11.24H16.17l-5.214-6.817L4.99 21.75H1.68l7.73-8.835L1.254 2.25H8.08l4.713 6.231zm-1.161 17.52h1.833L7.084 4.126H5.117z"/></svg>');
    case"line": return wrap("#06C755",'<svg viewBox="0 0 24 24" width="13" height="13" fill="#fff"><path d="M12 3C6.5 3 2 6.6 2 11c0 3.9 3.5 7.2 8.3 7.9.3.07.75.22.86.5.1.26.06.66.03.92l-.14.83c-.04.25-.2.97.85.53s5.64-3.32 7.7-5.68C20.9 14.9 22 13.06 22 11c0-4.4-4.5-8-10-8z"/></svg>');
    case"facebook": return wrap("#1877F2",'<svg viewBox="0 0 24 24" width="13" height="13" fill="#fff"><path d="M14 8.5V7c0-.7.5-1 1-1h1.5V3H14c-2.2 0-3.5 1.4-3.5 3.6V8.5H8V12h2.5v9H14v-9h2.3l.4-3.5H14z"/></svg>');
    case"mail": return wrap("var(--accent,#2AC1C7)",'<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="#fff" stroke-width="2"><rect x="3" y="5" width="18" height="14" rx="2"/><path d="M4 7l8 6 8-6" stroke-linecap="round"/></svg>');
    case"token": return wrap("#9AA0AA",'<svg viewBox="0 0 24 24" width="13" height="13" fill="#fff"><path d="M14 7a4 4 0 1 0-3.8 5H12v2h2v2h3v-3l-1-1h-1.2A4 4 0 0 0 14 7zM8 9a1.2 1.2 0 1 1 0 2.4A1.2 1.2 0 0 1 8 9z"/></svg>');
    default: return "";
  }
}
function koeMethodLabel(m){switch(m){case"mail":return"メアド";case"x":return"X";case"line":return"LINE";case"facebook":return"Facebook";case"token":return"トークン";default:return""}}/* 保存アカウント(トークン含む)は localStorage ではなくネイティブの暗号化ストア(Android Keystore)に保存。
   古い localStorage の内容は初回に移行して削除する。 */
function __secStore(){try{return window.AndroidApi&&window.AndroidApi.secureLoad?window.AndroidApi:null}catch(e){return null}}
function getAccounts(){try{var st=__secStore();if(st){var v=st.secureLoad("accounts");if(v&&v!=="null"){var arr=JSON.parse(v)||[];/* 移行: localStorage に残っていれば取り込んで消す */try{var legacy=localStorage.getItem("koe_accounts");if(legacy){var la=JSON.parse(legacy)||[];la.forEach(function(x){if(!arr.some(function(y){return y.user_id===x.user_id}))arr.push(x)});st.secureSave("accounts",JSON.stringify(arr));localStorage.removeItem("koe_accounts")}}catch(e){}return arr}var legacy2=localStorage.getItem("koe_accounts");if(legacy2){try{st.secureSave("accounts",legacy2);localStorage.removeItem("koe_accounts")}catch(e){}return JSON.parse(legacy2)||[]}return[]}return JSON.parse(localStorage.getItem("koe_accounts")||"[]")}catch(e){return[]}}
function saveAccounts(a){try{var st=__secStore();if(st){st.secureSave("accounts",JSON.stringify(a));try{localStorage.removeItem("koe_accounts")}catch(e){}return}localStorage.setItem("koe_accounts",JSON.stringify(a))}catch(e){}}function currentAccountId(){try{return parseInt(localStorage.getItem("koe_current_account")||"0",10)}catch(e){return 0}}async function saveCurrentAccount(){try{const r=await callApi("export_token");if(!r||!r.ok||!r.token)return;const id=r.user_id;if(!id)return;let name="";try{const li=await callApi("is_logged_in");name=li&&li.user_name||""}catch(e){}let a=getAccounts();const i=a.findIndex(x=>x.user_id===id),acc={user_id:id,token:r.token,name:name||(i>=0?a[i].name:"")||"user "+id,icon:i>=0?a[i].icon:"",method:(window.__koeLoginMethod||(i>=0?a[i].method:"")||"")};i>=0?a[i]=acc:a.push(acc),saveAccounts(a);try{localStorage.setItem("koe_current_account",String(id))}catch(e){}}catch(e){}}function renderAccounts(){const box=document.getElementById("accountsList");if(!box)return;const a=getAccounts(),cur=currentAccountId();a.length?(box.innerHTML=a.map(x=>`<div class="account-item ${x.user_id===cur?"current":""}">\n    ${avatarHtml(x.name,x.icon)}\n    <div class="account-info"><div class="account-name">${escapeHtml(x.name||"user "+x.user_id)}${x.user_id===cur?' <span class="account-cur">(現在)</span>':""}</div><div class="account-id">ID: ${x.user_id}${x.method?' <span class="account-method">'+koeMethodLabel(x.method)+'</span>':""}</div></div>\n    ${x.method?koeMethodIcon(x.method):""}${x.user_id===cur?"":`<button class="account-switch btn-secondary" data-id="${x.user_id}" style="width:auto;">切替</button>`}\n    <button class="account-remove" data-id="${x.user_id}" title="削除">✕</button>\n  </div>`).join(""),box.querySelectorAll(".account-switch").forEach(b=>b.addEventListener("click",()=>switchAccount(parseInt(b.dataset.id,10)))),box.querySelectorAll(".account-remove").forEach(b=>b.addEventListener("click",()=>{saveAccounts(getAccounts().filter(x=>x.user_id!==parseInt(b.dataset.id,10))),renderAccounts(),toast("削除しました")}))):box.innerHTML='<span class="pd-empty">保存されたアカウントはありません</span>'}async function switchAccount(id){const acc=getAccounts().find(x=>x.user_id===id);if(!acc)return;if(!await showConfirmModal(`${acc.name||"user "+id} に切り替えますか?`))return;const r=await callApi("login_with_token",acc.token,String(acc.user_id));if(r&&r.ok){try{localStorage.setItem("koe_current_account",String(id))}catch(e){}sfx("success"),toast("切り替えました"),setTimeout(()=>location.reload(),500)}else toast("切替に失敗(トークン期限切れの可能性)","error")}const THEME_PRESETS=[{name:"ティール",accent:"#2AC1C7",bg:"#111318"},{name:"夜桜",accent:"#F2568C",bg:"#1a1016"},{name:"海",accent:"#268aff",bg:"#0d1420"},{name:"森",accent:"#22C55E",bg:"#0e1613"},{name:"サンセット",accent:"#F5872A",bg:"#1a1310"},{name:"ラベンダー",accent:"#8B5CF6",bg:"#15121c"},{name:"モノクロ",accent:"#9AA0AA",bg:"#141414"},{name:"レトロ",accent:"#F5C542",bg:"#0a0a0a"}];function applyThemePreset(pr){try{"function"==typeof setAccentColor&&setAccentColor(pr.accent)}catch(e){}try{"function"==typeof setCustomBackground&&setCustomBackground(pr.bg)}catch(e){}sfx("toggle")}function renderThemePresets(){const box=document.getElementById("themePresets");box&&(box.innerHTML=THEME_PRESETS.map((pr,i)=>`<button class="theme-preset" data-i="${i}" title="${pr.name}" style="background:linear-gradient(135deg,${pr.bg} 55%,${pr.accent} 55%)"><span>${pr.name}</span></button>`).join(""),box.querySelectorAll(".theme-preset").forEach(b=>b.addEventListener("click",()=>applyThemePreset(THEME_PRESETS[parseInt(b.dataset.i,10)]))))}const EMOJI_SET=[];function toggleEmojiPicker(){return;let pk=document.getElementById("emojiPicker");if(pk)return void pk.remove();pk=document.createElement("div"),pk.id="emojiPicker",pk.className="emoji-picker",pk.innerHTML=EMOJI_SET.map(e=>`<button type="button" class="emoji-cell">${e}</button>`).join("");const ta=document.getElementById("composeText");pk.querySelectorAll(".emoji-cell").forEach(c=>c.addEventListener("click",()=>{if(ta){const st=ta.selectionStart||ta.value.length,en=ta.selectionEnd||ta.value.length;ta.value=ta.value.slice(0,st)+c.textContent+ta.value.slice(en),ta.dispatchEvent(new Event("input")),ta.focus(),ta.selectionStart=ta.selectionEnd=st+c.textContent.length}haptic(6)}));const btn=document.getElementById("composeEmojiBtn");btn&&btn.parentElement&&btn.parentElement.insertBefore(pk,btn.nextSibling)}function haptic(ms){try{if("off"===localStorage.getItem("koe_haptic"))return}catch(e){}try{navigator.vibrate&&navigator.vibrate(ms||10)}catch(e){}}function relTime(v){if(null==v||""===v)return"";let d;const sv=String(v);if(d=/^\d+$/.test(sv)?new Date(parseInt(sv,10)*(sv.length<=10?1e3:1)):new Date(sv),isNaN(d.getTime()))return escapeHtml(sv);try{if("off"===localStorage.getItem("koe_reltime")){const p2b=n=>String(n).padStart(2,"0");return`${d.getFullYear()}/${p2b(d.getMonth()+1)}/${p2b(d.getDate())} ${p2b(d.getHours())}:${p2b(d.getMinutes())}`}}catch(e){}let sec=Math.floor((Date.now()-d.getTime())/1e3);if(sec<0&&(sec=0),sec<60)return"たった今";if(sec<3600)return Math.floor(sec/60)+"分前";if(sec<86400)return Math.floor(sec/3600)+"時間前";if(sec<604800)return Math.floor(sec/86400)+"日前";const p2=n=>String(n).padStart(2,"0");return`${d.getFullYear()}/${p2(d.getMonth()+1)}/${p2(d.getDate())}`}function linkify(text){var s=String(text==null?"":text);var re=/(https?:\/\/[^\s<>"\']+)/g;var out="",last=0,m;while((m=re.exec(s))){out+=escAttr(s.slice(last,m.index));var u=m[1];out+='<a href="'+escAttr(u)+'" target="_blank" rel="noopener noreferrer" onclick="event.stopPropagation()">'+escAttr(u)+'</a>';last=m.index+m[0].length;}out+=escAttr(s.slice(last));return out;}function heartBurst(x,y){try{const h=document.createElement("div");h.className="heart-burst",h.innerHTML=void 0!==HEART_F_SVG?HEART_F_SVG:"",h.style.left=x+"px",h.style.top=y+"px",document.body.appendChild(h),setTimeout(()=>h.remove(),850)}catch(e){}}function openLightbox(src){let lb=document.getElementById("imageLightbox");if(!lb){lb=document.createElement("div"),lb.id="imageLightbox",lb.innerHTML='<img id="lightboxImg" alt="">'+(window.AndroidApi&&window.AndroidApi.saveImage?'<button id="lightboxSave" class="lightbox-save">画像を保存</button>':""),lb.addEventListener("click",e=>{"lightboxSave"!==e.target.id&&(lb.style.display="none")});const sv=lb.querySelector("#lightboxSave");sv&&sv.addEventListener("click",e=>{e.stopPropagation();try{window.AndroidApi.saveImage(document.getElementById("lightboxImg").src),toast("保存中…"),haptic(10)}catch(err){}}),document.body.appendChild(lb)}document.getElementById("lightboxImg").src=src,lb.style.display="flex",haptic(6);{const sv2=document.getElementById("lightboxSave");if(sv2)sv2.style.display=/^https?:\/\//i.test(String(src||""))?"":"none"}}async function reloadCurrentView(){const t=function(){try{return localStorage.getItem("koe_last_tab")||"timeline"}catch(e){return"timeline"}}();return"timeline"===t&&"function"==typeof loadTimeline?loadTimeline():"chat"===t&&"function"==typeof loadChats?loadChats():"notifications"===t&&"function"==typeof loadNotifications?loadNotifications(currentNotifKind):"community"===t&&"function"==typeof loadCommunities?loadCommunities():"call"===t&&"function"==typeof loadGroupRooms?loadGroupRooms():"talk"===t&&"function"==typeof loadCallRecords?loadCallRecords():"cheering"===t&&"function"==typeof loadReceivers?loadReceivers((document.querySelector(".cheering-kind-chip.active")||{dataset:{}}).dataset.kind||"recommended"):void 0}function refreshRelTimes(){if(document.hidden)return;document.querySelectorAll("[data-ts]").forEach(el=>{const t=el.getAttribute("data-ts");if(!t)return;var extra="";try{var ex=el.querySelector(".koe-tl-extra");if(ex)extra=ex.outerHTML}catch(e){}el.innerHTML=koeTimeLabel(t)+extra})}function koeImgRetry(img){try{var n=parseInt(img.getAttribute("data-retry")||"0",10);var ini=img.getAttribute("data-ini")||"?";if(n<2){img.setAttribute("data-retry",n+1);var base=(img.getAttribute("data-src")||img.src).split("?")[0];img.setAttribute("data-src",base);setTimeout(function(){try{img.src=base+"?r="+(n+1)+"_"+Date.now()}catch(e){}},700*(n+1))}else{img.outerHTML='<div class="avatar">'+ini+"</div>"}}catch(e){try{img.outerHTML='<div class="avatar">?</div>'}catch(e2){}}}function koeApplyBioClamp(id){try{var el=document.getElementById(id);if(!el)return;var wrapId=id+"ToggleLink";var old2=document.getElementById(wrapId);if(old2)old2.remove();el.classList.remove("bio-expanded");var txt=(el.textContent||"").trim();if(!txt){el.classList.remove("bio-clamp");return}el.classList.add("bio-clamp");function decide(){try{var lines=txt.split(/\r?\n/).length;var over=el.clientHeight>0?(el.scrollHeight>el.clientHeight+2):(lines>3||txt.length>105);if(!over){if(el.clientHeight>0){el.classList.remove("bio-clamp")}return}if(document.getElementById(wrapId))return;var link=document.createElement("span");link.id=wrapId;link.className="bio-toggle-link";link.textContent="詳しく読む";link.onclick=function(){var expanded=el.classList.toggle("bio-expanded");el.classList.toggle("bio-clamp",!expanded);link.textContent=expanded?"閉じる":"詳しく読む"};el.insertAdjacentElement("afterend",link)}catch(e){}}decide();setTimeout(decide,0);setTimeout(decide,600)}catch(e){}}
function avatarHtml(name,iconUrl){const initial=escapeHtml((name||"?").charAt(0).toUpperCase());return iconUrl?`<img class="avatar" loading="lazy" decoding="async" src="${escAttr(iconUrl)}" data-retry="0" data-ini="${initial}" onerror="koeImgRetry(this)">`:`<div class="avatar">${initial}</div>`}function skeletonCards(count){return Array(count||3).fill(0).map(()=>'\n    <div class="card skeleton-card" style="cursor:default;">\n      <div class="skeleton-avatar"></div>\n      <div class="card-body">\n        <div class="skeleton-line" style="width:40%;"></div>\n        <div class="skeleton-line" style="width:75%;"></div>\n      </div>\n    </div>\n  ').join("")}function toast(message,type){try{if(currentRoomId&&typeof koeChatSystem==="function"){var __ov=document.getElementById("callOverlay");if(__ov&&__ov.style.display!=="none"&&type!=="error"&&/参加|退出|手を挙げ|発言|枠名|閉じられ|許可|拒否|ミュート|招待|退室|入室/.test(String(message)))koeChatSystem(message)}}catch(e){}const container=document.getElementById("toastContainer"),el=document.createElement("div");if(el.className="toast"+("error"===type?" error":""),el.textContent=message,"error"===type){try{"off"!==localStorage.getItem("koe_haptic")&&navigator.vibrate&&navigator.vibrate([12,40,12])}catch(e){}sfx("error")}container.appendChild(el),setTimeout(()=>{el.classList.add("fadeout"),setTimeout(()=>el.remove(),260)},2600)}function toastAction(message,actionLabel,onAction,ms){const container=document.getElementById("toastContainer"),el=document.createElement("div");el.className="toast toast-action";const span=document.createElement("span");span.textContent=message;const btn=document.createElement("button");btn.className="toast-btn",btn.textContent=actionLabel;let done=!1;btn.addEventListener("click",()=>{if(!done){done=!0,el.remove(),haptic(10);try{onAction()}catch(e){}}}),el.appendChild(span),el.appendChild(btn),container.appendChild(el),setTimeout(()=>{el.classList.add("fadeout"),setTimeout(()=>el.remove(),260)},ms||5e3)}function showInputModal(title,placeholder){return new Promise(resolve=>{const modal=document.getElementById("inputModal"),field=document.getElementById("inputModalField");document.getElementById("inputModalTitle").textContent=title,field.value="",field.placeholder=placeholder||"",modal.style.display="flex",field.focus();const okBtn=document.getElementById("inputModalOk"),cancelBtn=document.getElementById("inputModalCancel"),closeBtn=document.getElementById("inputModalClose");try{if(okBtn.__koePrevOk)okBtn.removeEventListener("click",okBtn.__koePrevOk);if(cancelBtn.__koePrevCancel)cancelBtn.removeEventListener("click",cancelBtn.__koePrevCancel);if(closeBtn.__koePrevCancel)closeBtn.removeEventListener("click",closeBtn.__koePrevCancel);if(okBtn.__koePrevResolve)okBtn.__koePrevResolve(null);}catch(e){}const cleanup=()=>{modal.style.display="none",okBtn.removeEventListener("click",onOk),cancelBtn.removeEventListener("click",onCancel),closeBtn.removeEventListener("click",onCancel),okBtn.__koePrevOk=null,cancelBtn.__koePrevCancel=null,closeBtn.__koePrevCancel=null,okBtn.__koePrevResolve=null},onOk=()=>{const v=field.value.trim();cleanup(),resolve(v||null)},onCancel=()=>{cleanup(),resolve(null)};okBtn.__koePrevOk=onOk;cancelBtn.__koePrevCancel=onCancel;closeBtn.__koePrevCancel=onCancel;okBtn.__koePrevResolve=resolve;okBtn.addEventListener("click",onOk),cancelBtn.addEventListener("click",onCancel),closeBtn.addEventListener("click",onCancel)})}function showConfirmModal(text){return new Promise(resolve=>{const modal=document.getElementById("confirmModal");document.getElementById("confirmModalText").textContent=text;try{var _ok=document.getElementById("confirmModalOk");if(_ok){var _dg=/削除|ブロック|退会|解散|キック|ログアウト|退出|取り消|拒否|強制|やめ|停止/.test(text);_ok.className=_dg?"btn-danger":"btn-primary";_ok.textContent=_dg?"実行":"OK";}}catch(e){}modal.style.display="flex";var okBtn=document.getElementById("confirmModalOk"),cancelBtn=document.getElementById("confirmModalCancel");try{if(okBtn.__koePrevOk)okBtn.removeEventListener("click",okBtn.__koePrevOk);if(cancelBtn.__koePrevCancel)cancelBtn.removeEventListener("click",cancelBtn.__koePrevCancel);if(okBtn.__koePrevResolve)okBtn.__koePrevResolve(!1);}catch(e){}const cleanup=()=>{modal.style.display="none",okBtn.removeEventListener("click",onOk),cancelBtn.removeEventListener("click",onCancel),okBtn.__koePrevOk=null,cancelBtn.__koePrevCancel=null,okBtn.__koePrevResolve=null},onOk=()=>{cleanup(),resolve(!0)},onCancel=()=>{cleanup(),resolve(!1)};okBtn.__koePrevOk=onOk;cancelBtn.__koePrevCancel=onCancel;okBtn.__koePrevResolve=resolve;okBtn.addEventListener("click",onOk),cancelBtn.addEventListener("click",onCancel)})}function showScreen(id){document.querySelectorAll(".screen, .login-screen, .main-screen").forEach(el=>el.style.display="none"),document.getElementById(id).style.display="flex"}window.__nativeToast=function(m){try{toast(m)}catch(e){}};const PAGE_TITLES={timeline:"タイムライン",call:"グループ通話",cheering:"応援通話",chat:"チャット",community:"コミュニティ",talk:"トーク",notifications:"通知",mypage:"マイページ"};let currentNotifKind="normal";function notifTs(x){if(!x)return 0;var s=String(x);if(/^\d{9,13}$/.test(s)){var n=Number(s);return s.length<=10?n*1e3:n}var d=Date.parse(s);return isNaN(d)?0:d}async function checkNotifications(){if(document.hidden)return;try{var r=await callApi("get_notifications","normal");if(!r||!r.ok||!r.notifications)return;var seen=0;try{seen=Number(localStorage.getItem("koe_notif_seen"))||0}catch(e){}var newest=0,unseen=0;r.notifications.forEach(function(n){if(typeof isNotifTypeMuted==="function"&&isNotifTypeMuted(n.type))return;var t=notifTs(n.created_at);if(t>newest)newest=t;if(t>seen)unseen++});var badge=document.getElementById("notifBadge");if(badge){if(unseen>0){badge.textContent=unseen>99?"99+":String(unseen);badge.style.display="block"}else badge.style.display="none"}if(typeof window.__notifNewest==="number"&&newest>window.__notifNewest&&unseen>0){toast(" 新しい通知が"+unseen+"件");try{var __ns=localStorage.getItem("koe_notify_sound")||"chime";if(__ns!=="none")sfx(__ns)}catch(e){}}window.__notifNewest=newest}catch(e){}}function markNotifsSeen(){var newest=window.__notifNewest||Date.now();try{localStorage.setItem("koe_notif_seen",String(newest))}catch(e){}var b=document.getElementById("notifBadge");if(b)b.style.display="none"}function koeNotifHtml(n,i){const ti=notifTypeIcon(n.type),nm=n.name||"",msg=n.message||"";return`\n    <div class="notif-item" onclick="openNotifTarget(${i})" style="cursor:pointer;">\n      <div class="notif-av-wrap"${n.user_id?` onclick="event.stopPropagation();viewProfile(${parseInt(n.user_id,10)})" title="プロフィールを見る"`:""}>\n        ${avatarHtml(nm||"?",n.icon_url)}\n        <span class="notif-type-badge" style="background:${ti.color}">${ti.svg}</span>\n      </div>\n      <div class="notif-body">\n        <div class="notif-line">${nm?`<b>${escapeHtml(nm)}</b>`:""}${nm?" ":""}${escapeHtml(msg)}</div>\n        <div class="notif-time">${escapeHtml(relTime(n.created_at)||"")}</div>\n      </div>\n    </div>`}
function koeNotifMoreHtml(){if(window.__notifDone||("normal"!==currentNotifKind&&"important"!==currentNotifKind))return"";return'<div id="notifMoreWrap" style="text-align:center;padding:10px 0 14px;"><button id="notifMoreBtn" class="btn-secondary" style="width:auto;padding:8px 22px;">さらに前の通知を読み込む</button></div>'}
async function koeLoadMoreNotifs(){try{if(window.__notifLoadingMore||window.__notifDone)return;if("normal"!==currentNotifKind&&"important"!==currentNotifKind)return;window.__notifLoadingMore=true;var btn=document.getElementById("notifMoreBtn");if(btn){btn.disabled=true;btn.textContent="読み込み中…"}var page=(window.__notifPage||1)+1;var r=await callApi("get_notifications",currentNotifKind,String(page));var list=document.getElementById("notificationsList");var wrap=document.getElementById("notifMoreWrap");if(!r||!r.ok){if(btn){btn.disabled=false;btn.textContent="再試行"}window.__notifLoadingMore=false;return}var items=(r.notifications||[]);try{if(typeof isNotifTypeMuted==="function")items=items.filter(function(n){return !isNotifTypeMuted(n.type)})}catch(e){}var seen={};(window.__notifItems||[]).forEach(function(n){seen[(n.type||"")+"|"+(n.user_id||"")+"|"+(n.created_at||"")+"|"+(n.target_id||"")]=1});var fresh=items.filter(function(n){var k=(n.type||"")+"|"+(n.user_id||"")+"|"+(n.created_at||"")+"|"+(n.target_id||"");if(seen[k])return false;seen[k]=1;return true});window.__notifPage=page;if(!(r.notifications||[]).length||!fresh.length){window.__notifDone=true;if(wrap)wrap.innerHTML='<div class="card-sub" style="opacity:.6;padding:6px;">これ以上の通知はありません</div>';window.__notifLoadingMore=false;return}var base=(window.__notifItems||[]).length;window.__notifItems=(window.__notifItems||[]).concat(fresh);var html=fresh.map(function(n,i){return koeNotifHtml(n,base+i)}).join("");if(wrap){wrap.insertAdjacentHTML("beforebegin",html);wrap.remove()}else if(list){list.insertAdjacentHTML("beforeend",html)}if(list)list.insertAdjacentHTML("beforeend",koeNotifMoreHtml());window.__notifLoadingMore=false}catch(e){window.__notifLoadingMore=false}}
document.addEventListener("click",function(e){var b=e.target&&e.target.closest&&e.target.closest("#notifMoreBtn");if(b){e.preventDefault();koeLoadMoreNotifs()}});
(function(){function w(){document.querySelectorAll(".content-body").forEach(function(cb){if(cb.__notifScroll)return;cb.__notifScroll=1;cb.addEventListener("scroll",function(){try{var pg=document.getElementById("page-notifications");if(!pg||!pg.classList.contains("active"))return;if(cb.scrollTop+cb.clientHeight>=cb.scrollHeight-300)koeLoadMoreNotifs()}catch(e){}},{passive:true})})}if(document.readyState==="loading")document.addEventListener("DOMContentLoaded",w);else w()})();
async function loadNotifications(kind){currentNotifKind=kind||"normal",document.querySelectorAll(".notif-kind-chip").forEach(c=>c.classList.toggle("active",c.dataset.kind===currentNotifKind));const list=document.getElementById("notificationsList");list.innerHTML=skeletonCards(4);let result;if("calls"===currentNotifKind){const __rr=await Promise.all([callApi("get_missed_calls"),callApi("get_talk_requests")]);let __m=[];if(__rr[0]&&__rr[0].ok)__m=__m.concat((__rr[0].items||[]).map(function(u){return{type:101,user_id:u.user_id,name:u.name,icon_url:u.icon_url,message:"から着信がありました",created_at:u.created_at}}));if(__rr[1]&&__rr[1].ok)__m=__m.concat((__rr[1].items||[]).map(function(u){return{type:102,user_id:u.user_id,name:u.name,icon_url:u.icon_url,message:"からトークリクエストが届きました",created_at:u.created_at}}));__m.sort(function(x,y){return String(y.created_at||"").localeCompare(String(x.created_at||""))});result={ok:!!(__rr[0]&&__rr[0].ok||__rr[1]&&__rr[1].ok),notifications:__m}}else{result="follow"===currentNotifKind?await callApi("get_follow_activity"):await callApi("get_notifications",currentNotifKind)}if(!result.ok)return void(list.innerHTML=`<div class="empty-state"><div class="empty-ico">${BELL_SVG}</div>読み込みに失敗しました<br><small style="opacity:.55;">HTTP ${result.status||"?"}</small></div>`);let items=result.notifications||[];try{if(typeof isNotifTypeMuted==="function")items=items.filter(function(n){return !isNotifTypeMuted(n.type);});}catch(e){}items.length?(window.__notifItems=items,window.__notifPage=1,window.__notifDone=items.length<10,list.innerHTML=items.map((n,i)=>koeNotifHtml(n,i)).join("")+koeNotifMoreHtml()):list.innerHTML=`<div class="empty-state"><div class="empty-ico">${BELL_SVG}</div>${"important"===currentNotifKind?"重要なお知らせはありません":"follow"===currentNotifKind?"フォロー中のユーザーの新しい投稿はありません":"calls"===currentNotifKind?"着信・トークリクエストはありません":"通知はありません"}</div>`}function openNotifTarget(i){const n=(window.__notifItems||[])[i];if(!n)return;const t=parseInt(n.type,10);sfx("open");
/* サークル(コミュニティ)系(11〜20)は community_id があればそのコミュニティを開く。20=サークル内の通話枠作成 */
if(t>=11&&t<=20&&n.community_id){return openCommunity(String(n.community_id),n.community_name||n.name||"",!0)}
/* 9=枠作成 / 10=枠への招待: 通話ページを開いてその人の枠に参加を促す */
if((t===9||t===10)&&(n.room_id||n.user_id)){showPage("call");setTimeout(async function(){try{if(await showConfirmModal((n.name||"この人")+" の枠に参加しますか?")){if(n.room_id)joinRoomById(n.room_id,n.user_id);else joinGroupRoom(n.user_id)}}catch(e){}},400);return}
var __fp=n.feed_post_id||n.target_id;if(__fp&&(t===0||t===1||t===100||t===2||t===3||!n.user_id))return openPostDetail(null,__fp);if((0===t||1===t||100===t)&&__fp)return openPostDetail(null,__fp);if((101===t||102===t)&&n.user_id)return viewProfile(n.user_id);if(4===t){if(n.chat_id&&n.user_id)return openChat(String(n.chat_id),String(n.user_id),n.name||"",n.icon_url);if(n.user_id)return viewProfile(n.user_id)}return 5!==t&&6!==t||!n.user_id?t>=11&&t<=20&&n.community_id?openCommunity(String(n.community_id),n.name||"",!0):n.user_id?viewProfile(n.user_id):void(showToast&&showToast("この通知には開ける内容がありません")):viewProfile(n.user_id)}function setupPullToRefresh(){const scroller=document.querySelector(".content-body"),container=document.querySelector(".content");if(!scroller||!container||scroller.__ptr)return;scroller.__ptr=!0;const ind=document.createElement("div");ind.className="ptr-indicator",ind.innerHTML='<div class="ptr-spinner"></div>',container.appendChild(ind);let startY=0,pulling=!1,dist=0,refreshing=!1;const hide=()=>{ind.style.transition="transform .25s ease, opacity .25s ease",ind.style.opacity="0",ind.style.transform="translateX(-50%) translateY(-40px)",ind.classList.remove("ready")};scroller.addEventListener("touchstart",e=>{refreshing||scroller.scrollTop>0?pulling=!1:(startY=e.touches[0].clientY,pulling=!0,dist=0,ind.style.transition="none")},{passive:!0}),scroller.addEventListener("touchmove",e=>{if(!pulling||refreshing)return;const raw=e.touches[0].clientY-startY;raw<=0||scroller.scrollTop>0||(e.preventDefault(),dist=Math.min(.5*raw,90),ind.style.opacity=String(Math.min(dist/65,1)),ind.style.transform=`translateX(-50%) translateY(${Math.min(dist,64)-34}px) rotate(${4*dist}deg)`,ind.classList.toggle("ready",dist>=65))},{passive:!1}),scroller.addEventListener("touchend",()=>{if(pulling&&!refreshing)if(pulling=!1,dist>=65){refreshing=!0,ind.style.transition="transform .2s ease",ind.style.opacity="1",ind.style.transform="translateX(-50%) translateY(14px)",ind.classList.remove("ready"),ind.classList.add("spin");try{sfx("refresh")}catch(e){}Promise.resolve().then(()=>reloadCurrentView()).finally(()=>{setTimeout(()=>{ind.classList.remove("spin"),hide(),refreshing=!1},600)})}else hide()},{passive:!0})}const BELL_SVG='<svg viewBox="0 0 24 24" width="34" height="34" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.7 21a2 2 0 0 1-3.4 0"/></svg>';function notifTypeIcon(type){const t=parseInt(type,10),chat='<svg viewBox="0 0 24 24" fill="#fff" width="11" height="11"><path d="M4 4h16v11H8l-4 4V4z"/></svg>';return 1===t||17===t||18===t?{color:"#F1436B",svg:'<svg viewBox="0 0 24 24" fill="#fff" width="11" height="11"><path d="M12 21s-7-4.5-9.5-8.5C.5 9 2 5.5 5.5 5.5c2 0 3.2 1.2 3.5 2 .3-.8 1.5-2 3.5-2C16 5.5 17.5 9 15.5 12.5 13 16.5 12 21 12 21z"/></svg>'}:0===t||13===t||2===t?{color:"#3B9EFF",svg:chat}:4===t?{color:"#2AC1C7",svg:chat}:5===t||6===t?{color:"#3FBF6B",svg:'<svg viewBox="0 0 24 24" fill="#fff" width="11" height="11"><circle cx="12" cy="8" r="4"/><path d="M4 21c0-4 4-6 8-6s8 2 8 6z"/></svg>'}:7===t?{color:"#E9B23C",svg:'<svg viewBox="0 0 24 24" fill="#fff" width="11" height="11"><path d="M4 9h16v11H4zM3 5h18v4H3zM12 5v15"/></svg>'}:9===t||10===t?{color:"#F0883E",svg:'<svg viewBox="0 0 24 24" fill="#fff" width="11" height="11"><rect x="9" y="3" width="6" height="10" rx="3"/><path d="M6 11a6 6 0 0 0 12 0" fill="none" stroke="#fff" stroke-width="2"/></svg>'}:21===t||22===t?{color:"#F0883E",svg:'<svg viewBox="0 0 24 24" fill="#fff" width="11" height="11"><path d="M4 10v4l10 4V6L4 10zM16 8a4 4 0 0 1 0 8"/></svg>'}:t>=11&&t<=20?{color:"#9B6DFF",svg:'<svg viewBox="0 0 24 24" fill="#fff" width="11" height="11"><circle cx="8" cy="9" r="3"/><circle cx="16" cy="9" r="3"/><path d="M2 20c0-3 3-5 6-5s6 2 6 5M14 20c0-2 1-3 2-4 3 0 6 2 6 5"/></svg>'}:{color:"#6B7280",svg:chat}}const NAV_ORDER_KEY="koetomo_nav_order",NAV_DEFAULT=["timeline","call","cheering","chat","talk","community","notifications","mypage"],NAV_LABELS={timeline:"タイムライン",call:"グループ通話",cheering:"応援通話",chat:"チャット",community:"コミュニティ",talk:"トーク",notifications:"通知",mypage:"マイページ"};function getNavOrder(){try{const o=JSON.parse(localStorage.getItem(NAV_ORDER_KEY));if(Array.isArray(o)){const full=o.filter(v=>NAV_DEFAULT.includes(v));return NAV_DEFAULT.forEach(v=>{full.includes(v)||full.push(v)}),full}}catch(e){}return NAV_DEFAULT.slice()}function applyNavOrder(){const rail=document.querySelector(".rail");if(!rail)return;getNavOrder().forEach(view=>{const it=rail.querySelector(`.rail-item[data-view="${view}"]`);it&&rail.appendChild(it)});const divider=rail.querySelector(".rail-divider");divider&&rail.appendChild(divider)}function renderNavOrderEditor(){const list=document.getElementById("navOrderList");if(!list)return;const order=getNavOrder();list.innerHTML=order.map((v,i)=>`<div class="nav-order-item"><span>${escapeHtml(NAV_LABELS[v]||v)}</span><span class="nav-order-btns"><button data-move="up" data-view="${v}" ${0===i?"disabled":""}>↑</button><button data-move="down" data-view="${v}" ${i===order.length-1?"disabled":""}>↓</button></span></div>`).join("")}function moveNavItem(view,dir){const order=getNavOrder(),idx=order.indexOf(view),sw="up"===dir?idx-1:idx+1;if(sw<0||sw>=order.length)return;const t=order[idx];order[idx]=order[sw],order[sw]=t,localStorage.setItem(NAV_ORDER_KEY,JSON.stringify(order)),applyNavOrder()}function __initUiExtras(){{const pdm=document.getElementById("postDetailModal"),closePd=()=>{pdm&&(pdm.style.display="none")},c=document.getElementById("postDetailClose");c&&(c.onclick=closePd),pdm&&pdm.addEventListener("click",e=>{e.target===pdm&&closePd()})}{const b=document.getElementById("pdReplyBtn");b&&b.addEventListener("click",sendPostDetailReply)}{const i=document.getElementById("pdReplyInput");i&&i.addEventListener("keydown",e=>{"Enter"===e.key&&(e.ctrlKey||e.metaKey)&&(e.preventDefault(),sendPostDetailReply())})}applyNavOrder();{const bs=document.getElementById("bookmarksSection");bs&&bs.addEventListener("toggle",function(){this.open&&loadBookmarks()})}{const bl=document.getElementById("blockedSection");bl&&bl.addEventListener("toggle",function(){this.open&&loadBlockedUsers()})}{const ps=document.getElementById("privacySection");ps&&ps.addEventListener("toggle",function(){this.open&&loadUserSettings()})}{const gs=document.getElementById("giftSection");gs&&gs.addEventListener("toggle",function(){this.open&&loadGiftHistory()})}{const vb=document.getElementById("composeVoiceBtn");vb&&vb.addEventListener("click",toggleVoiceRecord)}{const vc=document.getElementById("composeVoiceClear");vc&&vc.addEventListener("click",clearComposeVoice)}document.addEventListener("click",e=>{const b=e.target.closest&&e.target.closest(".voice-dl");b&&b.dataset.voiceUrl&&(e.preventDefault(),e.stopPropagation(),showDlFormat(b.dataset.voiceUrl))}),document.addEventListener("click",e=>{const b=e.target.closest&&e.target.closest(".aplayer-btn");b&&(e.preventDefault(),e.stopPropagation(),toggleAudioPlayer(b.closest(".aplayer")))}),document.addEventListener("click",e=>{const bar=e.target.closest&&e.target.closest(".aplayer-bar");if(bar){const pl=bar.closest(".aplayer");if(__curPlayer===pl&&__curAudio&&__curAudio.duration){const r=bar.getBoundingClientRect();__curAudio.currentTime=Math.max(0,Math.min(1,(e.clientX-r.left)/r.width))*__curAudio.duration}}}),document.querySelectorAll(".talk-src-chip").forEach(chip=>chip.addEventListener("click",()=>loadCallRecords(chip.dataset.talkSrc))),document.querySelectorAll(".dl-fmt-btn").forEach(b=>b.addEventListener("click",()=>doDownloadFmt(window.__dlUrl,b.dataset.fmt)));{const dc=document.getElementById("dlFormatClose");dc&&dc.addEventListener("click",()=>document.getElementById("dlFormatSheet").style.display="none")}{const dm=document.getElementById("dlFormatSheet");dm&&dm.addEventListener("click",e=>{e.target===dm&&(dm.style.display="none")})}{const rb=document.getElementById("recordCommentSend");rb&&rb.addEventListener("click",postRecordCommentAction)}{const ri=document.getElementById("recordCommentInput");ri&&ri.addEventListener("keydown",e=>{"Enter"===e.key&&(e.ctrlKey||e.metaKey)&&(e.preventDefault(),postRecordCommentAction())})}{const rc=document.getElementById("recordCommentClose");rc&&rc.addEventListener("click",()=>document.getElementById("recordCommentModal").style.display="none")}{const rm=document.getElementById("recordCommentModal");rm&&rm.addEventListener("click",e=>{e.target===rm&&(rm.style.display="none")})}{const fs=document.getElementById("friendsSection");fs&&fs.addEventListener("toggle",function(){this.open&&loadFriends()})}{const ub=document.getElementById("userSearchBtn");if(ub)ub.addEventListener("click",doUserSearch);const ui=document.getElementById("userSearchInput");if(ui)ui.addEventListener("keydown",function(e){if(e.key==="Enter")doUserSearch()});const ws=document.getElementById("walletSection");if(ws)ws.addEventListener("toggle",function(){if(this.open)loadWalletHistory()});const pe=document.getElementById("pointExchangeBtn");if(pe)pe.addEventListener("click",openPointExchange);const ch=document.getElementById("callHistorySection");if(ch)ch.addEventListener("toggle",function(){if(this.open)loadCallHistory()});{const sm=document.getElementById("spamMarkChk");if(sm){try{sm.checked=koeSpamEnabled()}catch(e){}sm.addEventListener("change",function(){try{localStorage.setItem("koe_spam_mark",sm.checked?"1":"0")}catch(e){}try{loadTimeline(false)}catch(e){}})}const dc=document.getElementById("koeDeletedClearBtn");if(dc)dc.addEventListener("click",async function(){if(await showConfirmModal("削除された投稿の記録を消しますか？")){try{localStorage.removeItem("koe_deleted_posts")}catch(e){}koeRenderDeletedPosts()}});const ds=document.getElementById("koeDeletedSection");if(ds)ds.addEventListener("toggle",function(){if(this.open)koeRenderDeletedPosts()})}
{const ab=document.getElementById("koeActCollectBtn");if(ab)ab.addEventListener("click",async function(){ab.disabled=true;ab.textContent="集計中…";try{var n=await koeActivityCollect();toast("集計しました(新規 "+(n||0)+" 件)")}catch(e){}ab.disabled=false;ab.textContent="いま集計"});const ac=document.getElementById("koeActClearBtn");if(ac)ac.addEventListener("click",function(){try{localStorage.removeItem("koe_act_hours");localStorage.removeItem("koe_act_seen")}catch(e){}koeActivityRender();toast("リセットしました")});const as=document.getElementById("koeActSection");if(as)as.addEventListener("toggle",function(){if(this.open)koeActivityRender()})}
const clg=document.getElementById("callLogSection");if(clg)clg.addEventListener("toggle",function(){if(this.open)loadCallLogs()});const mp=document.getElementById("myPostsSection");if(mp)mp.addEventListener("toggle",function(){if(this.open)loadPostsInto("myPostsList",myUserId||currentAccountId())})}{const la=document.getElementById("logoutActionSelect");if(la){try{la.value=localStorage.getItem("koe_logout_action")||"select"}catch(e){}la.addEventListener("change",()=>{try{localStorage.setItem("koe_logout_action",la.value)}catch(e){}sfx("toggle")})}}document.querySelectorAll(".modal").forEach(m=>{m.addEventListener("click",e=>{e.target===m&&(m.style.display="none",sfx("close"))})}),document.addEventListener("click",e=>{const av=e.target.closest&&e.target.closest(".callv2-pav[data-uid]");if(av&&Number(av.dataset.uid)>0){e.stopPropagation();try{viewProfile(Number(av.dataset.uid))}catch(er){}}}),document.addEventListener("click",e=>{e.target&&e.target.classList&&e.target.classList.contains("modal")&&"none"!==e.target.style.display&&(e.target.style.display="none")}),document.addEventListener("click",e=>{[["callChatPanel","callChatToggle"],["callSettingsPanel","callSettingsToggle"]].forEach(([pid,tid])=>{const panel=document.getElementById(pid);if(panel&&"none"!==panel.style.display&&!panel.contains(e.target)&&(!e.target.closest||!e.target.closest("#"+tid))){panel.style.display="none";const t=document.getElementById(tid);t&&t.classList.remove("active")}})}),document.addEventListener("click",e=>{e.target.closest&&e.target.closest(".modal-close")&&sfx("close")}),["composeSubmit","pdReplyBtn","loginBtn","callChatSendBtn"].forEach(id=>{const b=document.getElementById(id);b&&b.addEventListener("click",()=>haptic(10))}),function(){const t=document.getElementById("composeText");if(!t)return;let c=document.getElementById("composeCounter");c||(c=document.createElement("div"),c.id="composeCounter",c.className="compose-counter",t.parentNode.insertBefore(c,t.nextSibling));const upd=()=>{var n=(t.value||"").length;c.textContent=n+" 文字";var ct=document.getElementById("composeCounterTop");if(ct)ct.textContent=n+" / 500";try{localStorage.setItem("koe_draft",t.value||"")}catch(e){}t.style.height="auto",t.style.height=Math.min(t.scrollHeight,240)+"px"};t.addEventListener("input",upd),upd()}(),setInterval(refreshRelTimes,6e4),setInterval(checkNotifications,6e4),setTimeout(checkNotifications,3e3),setTimeout(initSpeakIndicator,0),function(){const fab=document.createElement("button");fab.id="scrollTopFab",fab.className="scrolltop-fab",fab.setAttribute("aria-label","最上部へ"),fab.innerHTML='<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M12 19V5M5 12l7-7 7 7"/></svg>',fab.addEventListener("click",()=>{document.querySelectorAll(".content-body, .page").forEach(cb=>{try{cb.scrollTo({top:0,behavior:"smooth"})}catch(e){cb.scrollTop=0}}),haptic(8)}),document.body.appendChild(fab);const onScroll=e=>{const st=e.target.scrollTop||0;fab.classList.toggle("show",st>400)};document.querySelectorAll(".content-body").forEach(cb=>cb.addEventListener("scroll",onScroll,{passive:!0}))}(),function(){let timer=null;document.addEventListener("touchstart",e=>{const el=e.target.closest(".tl-text, .pd-comment-text, .chat-msg .body, .chat-bubble");el&&(timer=setTimeout(async()=>{try{await navigator.clipboard.writeText(el.innerText||el.textContent||""),toast("コピーしました"),haptic(15)}catch(err){}},550))},{passive:!0});const clr=()=>{timer&&(clearTimeout(timer),timer=null)};document.addEventListener("touchend",clr,{passive:!0}),document.addEventListener("touchmove",clr,{passive:!0})}();{const t=document.getElementById("composeText");t&&t.addEventListener("keydown",e=>{(e.ctrlKey||e.metaKey)&&"Enter"===e.key&&(e.preventDefault(),submitComposePost())})}!function(){/* タブ間ページ移動のスワイプは無効化。左右スワイプはタイムラインのフォロー中/オープン切替のみ。 */}(),function(){const b=document.createElement("div");b.id="offlineBanner",b.className="offline-banner",b.textContent="オフラインです。接続を確認しています…",document.body.appendChild(b);const set=off=>b.classList.toggle("show",off);window.addEventListener("offline",()=>set(!0)),window.addEventListener("online",()=>{set(!1);try{reloadCurrentView()}catch(e){}toast("オンラインに復帰しました")}),navigator.onLine||set(!0)}();{const h=document.getElementById("hapticChk");if(h){try{h.checked="off"!==localStorage.getItem("koe_haptic")}catch(e){}h.addEventListener("change",()=>{try{localStorage.setItem("koe_haptic",h.checked?"on":"off")}catch(e){}h.checked&&haptic(15)})}}{const r=document.getElementById("fontSizeRange"),lbl=document.getElementById("fontSizeLabel");if(r){try{const fs=localStorage.getItem("koe_fontsize");fs&&(r.value=fs)}catch(e){}lbl&&(lbl.textContent=r.value+"%"),r.addEventListener("input",()=>{document.documentElement.style.fontSize=r.value+"%",lbl&&(lbl.textContent=r.value+"%");try{localStorage.setItem("koe_fontsize",r.value)}catch(e){}})}}function bindChk(id,key,onName,offName,cb){const el=document.getElementById(id);if(el){try{el.checked=localStorage.getItem(key)!==(offName||"off")}catch(e){}if("koe_datasaver"===key)try{el.checked="on"===localStorage.getItem(key)}catch(e){}el.addEventListener("change",()=>{const val=el.checked?onName||"on":offName||"off";try{localStorage.setItem(key,val)}catch(e){}cb&&cb(el.checked),el.checked&&sfx("toggle")});try{cb&&cb(el.checked)}catch(e){}}}bindChk("soundChk","koe_sound","on","off"),bindChk("animChk","koe_anim","on","off",on=>document.body.classList.toggle("no-anim",!on)),bindChk("relTimeChk","koe_reltime","on","off"),bindChk("newPostChk","koe_newpost","on","off"),bindChk("dataSaverChk","koe_datasaver","on","off",on=>document.body.classList.toggle("data-saver",on));{const r=document.getElementById("soundVolRange"),l=document.getElementById("soundVolLabel");if(r){try{const v=localStorage.getItem("koe_sound_vol");null!=v&&(r.value=Math.round(100*parseFloat(v)))}catch(e){}l&&(l.textContent=r.value+"%"),r.addEventListener("input",()=>{try{localStorage.setItem("koe_sound_vol",(r.value/100).toString())}catch(e){}l&&(l.textContent=r.value+"%")})}}{const b=document.getElementById("soundTestBtn");b&&b.addEventListener("click",()=>sfx("post"))}{const b=document.getElementById("soundTestAllBtn");b&&b.addEventListener("click",()=>{["like","post","send","message","notify","join","leave","bookmark","follow","success","open","close","error","refresh","mute","unmute","tab","chime","ding","bell","coin","gift","sparkle","pop","boop","whoosh","levelup","achievement","select","cancel","warning","heart","kick","alert","msg_in","swoosh_up"].forEach((n,i)=>setTimeout(()=>sfx(n),380*i))})}{const r=document.getElementById("speakSensRange"),l=document.getElementById("speakSensLabel");if(r){try{const v=localStorage.getItem("koe_speaksens");v&&(r.value=v)}catch(e){}const lab=n=>n<=3?"鈍い":n>=8?"敏感":"標準";l&&(l.textContent=lab(parseInt(r.value,10))),r.addEventListener("input",()=>{try{localStorage.setItem("koe_speaksens",r.value)}catch(e){}l&&(l.textContent=lab(parseInt(r.value,10)))})}}{const sel=document.getElementById("soundThemeSelect");if(sel){try{sel.value=localStorage.getItem("koe_sound_theme")||"default"}catch(e){}sel.addEventListener("change",()=>{try{localStorage.setItem("koe_sound_theme",sel.value)}catch(e){}sfx("post")})}}{const sel=document.getElementById("fontSelect"),ci=document.getElementById("fontCustomInput");if(sel){try{sel.value=localStorage.getItem("koe_font")||"system"}catch(e){}const syncCustom=()=>{ci&&(ci.style.display="custom"===sel.value?"block":"none")};syncCustom();try{ci&&(ci.value=localStorage.getItem("koe_font_custom")||"")}catch(e){}sel.addEventListener("change",()=>{try{localStorage.setItem("koe_font",sel.value)}catch(e){}syncCustom(),applyFont(),sfx("toggle")}),ci&&ci.addEventListener("input",()=>{try{localStorage.setItem("koe_font_custom",ci.value)}catch(e){}"custom"===sel.value&&applyFont()})}}bindChk("densityChk","koe_density","compact","normal",on=>document.body.classList.toggle("compact",on));{const d=document.getElementById("densityChk");if(d)try{d.checked="compact"===localStorage.getItem("koe_density")}catch(e){}}bindChk("confirmLeaveChk","koe_confirmleave","on","off");{const t=document.getElementById("ngWordsInput");if(t){try{t.value=localStorage.getItem("koe_ngwords")||""}catch(e){}t.addEventListener("input",()=>{try{localStorage.setItem("koe_ngwords",t.value)}catch(e){}})}}window.renderMutedUsers=async function(){const box=document.getElementById("mutedUsersList");if(!box)return;const ids=getMutedUsers();ids.length?(box.innerHTML=ids.map(id=>`<div class="muted-user-item"><span>ID: ${id}</span><button data-id="${id}" class="theme-btn" style="width:auto;">解除</button></div>`).join(""),box.querySelectorAll("button[data-id]").forEach(b=>b.addEventListener("click",()=>{toggleMuteUser(b.dataset.id),renderMutedUsers(),toast("ミュート解除")}))):box.innerHTML='<span class="pd-empty">なし</span>'};{const rb=document.getElementById("resetSettingsBtn");rb&&rb.addEventListener("click",async()=>{if(await showConfirmModal("すべての設定を初期化しますか?(ログイン状態は保持されます)")){try{Object.keys(localStorage).filter(k=>k.startsWith("koe_")&&"koe_last_tab"!==k).forEach(k=>localStorage.removeItem(k))}catch(e){}toast("設定をリセットしました"),setTimeout(()=>location.reload(),600)}})}document.addEventListener("keydown",e=>{if(e.ctrlKey||e.metaKey||e.altKey)return;const typing=e.target.matches&&e.target.matches("input, textarea, select")||e.target.isContentEditable;if("?"===e.key&&!typing)return e.preventDefault(),void function(){let o=document.getElementById("shortcutsHelp");o?o.style.display="flex"===o.style.display?"none":"flex":(o=document.createElement("div"),o.id="shortcutsHelp",o.className="modal",o.style.display="flex",o.innerHTML='<div class="modal-content"><div class="modal-header"><span>キーボードショートカット</span><button class="modal-close" onclick="document.getElementById(\'shortcutsHelp\').style.display=\'none\'">✕</button></div><div class="modal-body"><table class="sc-table"><tr><td>N</td><td>新規投稿</td></tr><tr><td>/</td><td>ユーザー検索</td></tr><tr><td>1〜7</td><td>タブ切替</td></tr><tr><td>T</td><td>最上部へ</td></tr><tr><td>R</td><td>再読み込み</td></tr><tr><td>Esc</td><td>閉じる</td></tr><tr><td>?</td><td>このヘルプ</td></tr></table></div></div>',o.addEventListener("click",e=>{e.target===o&&(o.style.display="none")}),document.body.appendChild(o))}();if(typing)return;if([...document.querySelectorAll(".modal")].some(m=>"flex"===m.style.display))return;const order="function"==typeof getNavOrder?getNavOrder():[];if(e.key>="1"&&e.key<="9"){const i=parseInt(e.key,10)-1;return void(order[i]&&(showPage(order[i]),sfx("tab")))}switch((e.key||"").toLowerCase()){case"n":"function"==typeof openComposeModal&&openComposeModal();break;case"/":e.preventDefault();{const b=document.getElementById("userSearchBtn");b&&b.click()}break;case"t":document.querySelectorAll(".content-body").forEach(cb=>{try{cb.scrollTo({top:0,behavior:"smooth"})}catch(_){cb.scrollTop=0}});break;case"r":"function"==typeof reloadCurrentView&&reloadCurrentView()}}),function(){let loading=!1;document.querySelectorAll(".content-body").forEach(cb=>{cb.addEventListener("scroll",()=>{document.getElementById("timelineLoadMoreRow")&&!loading&&cb.scrollTop+cb.clientHeight>=cb.scrollHeight-320&&(loading=!0,Promise.resolve(loadTimeline(!0)).finally(()=>{setTimeout(()=>{loading=!1},400)}))},{passive:!0})})}(),function(){const pill=document.createElement("button");pill.id="newPostsPill",pill.className="newposts-pill",pill.textContent="↑ 新しい投稿",pill.addEventListener("click",async()=>{pill.classList.remove("show"),await loadTimeline(),document.querySelectorAll(".content-body").forEach(cb=>{try{cb.scrollTo({top:0,behavior:"smooth"})}catch(e){cb.scrollTop=0}}),haptic(10)}),document.body.appendChild(pill),setInterval(async()=>{try{if(document.hidden)return;if("off"===localStorage.getItem("koe_newpost"))return;if("timeline"!==(localStorage.getItem("koe_last_tab")||"timeline"))return;if(typeof timelineFeed!=="undefined"&&timelineFeed&&timelineFeed!=="all")return;if([...document.querySelectorAll(".modal")].some(m=>"flex"===m.style.display))return;const call=document.getElementById("callOverlay");if(call&&"flex"===call.style.display)return;const r=await callApi("get_timeline");if(r&&r.ok&&r.posts&&r.posts.length){const top=r.posts[0].id;window.__newestPostId&&top&&top!==window.__newestPostId&&(pill.classList.add("show"),sfx("notify"))}}catch(e){}},9e4)}(),window.__koeHandleBack=function(){
  try{
    var lb=document.getElementById("imageLightbox");
    if(lb&&lb.style.display==="flex"){lb.style.display="none";return true;}
    var open=[].slice.call(document.querySelectorAll(".modal")).filter(function(m){return m.style.display==="flex"});
    if(open.length){open[open.length-1].style.display="none";try{sfx("close")}catch(e){}return true;}
    var co=document.getElementById("callOverlay");
    var inCallNow=(typeof skCurrentRoomId!=="undefined"&&skCurrentRoomId)||(typeof skRoom!=="undefined"&&skRoom);
    if(co&&co.style.display==="flex"&&inCallNow){
      try{minimizeCall()}catch(e){}
      try{showPage("call")}catch(e){}
      return true;
    }
    var lastTab=(function(){try{return localStorage.getItem("koe_last_tab")||"timeline"}catch(e){return"timeline"}})();
    if(lastTab!=="timeline"){
      try{showPage("timeline")}catch(e){}
      return true;
    }
    return false;
  }catch(e){return false;}
};
document.addEventListener("keydown",e=>{if("Escape"===e.key){const lb=document.getElementById("imageLightbox");if(lb&&"flex"===lb.style.display)return void(lb.style.display="none");const open=[...document.querySelectorAll(".modal")].filter(m=>"flex"===m.style.display);open.length&&(open[open.length-1].style.display="none")}}),function(){let sy=0,tracking=!1,target=null;document.addEventListener("touchstart",e=>{const mc=e.target.closest(".modal-content"),modal=e.target.closest(".modal");mc&&modal&&"flex"===modal.style.display&&mc.scrollTop<=0&&(sy=e.touches[0].clientY,tracking=!0,target=modal)},{passive:!0});let sx0=0;document.addEventListener("touchstart",e=>{sx0=e.touches[0].clientX},{passive:!0}),document.addEventListener("touchmove",e=>{if(!tracking||!target)return;const dy=e.touches[0].clientY-sy,dx=e.touches[0].clientX-sx0,mc=target.querySelector(".modal-content");mc&&(dx>Math.abs(dy)&&dx>0?mc.style.transform=`translateX(${Math.min(dx,240)}px)`:dy>0&&(mc.style.transform=`translateY(${Math.min(dy,200)}px)`))},{passive:!0}),document.addEventListener("touchend",e=>{if(!tracking||!target)return;const dy=e.changedTouches[0].clientY-sy,dx=e.changedTouches[0].clientX-sx0,mc=target.querySelector(".modal-content");mc&&(mc.style.transform=""),(dy>110||dx>120)&&(target.style.display="none",haptic(8)),tracking=!1,target=null},{passive:!0})}();{const sb=document.getElementById("userSearchBtn");sb&&sb.addEventListener("click",async()=>{let hist=[];try{hist=JSON.parse(localStorage.getItem("koe_search_hist")||"[]")}catch(e){}let row=document.getElementById("searchRecentRow");row&&row.remove();const field=document.getElementById("inputModalField");hist.length&&field&&(row=document.createElement("div"),row.id="searchRecentRow",row.className="search-recent",row.innerHTML='<span class="sr-label">最近:</span>'+hist.slice(0,6).map(id=>`<button type="button" class="sr-chip" data-id="${id}">${id}</button>`).join(""),field.insertAdjacentElement("afterend",row),row.querySelectorAll(".sr-chip").forEach(c=>c.addEventListener("click",()=>{field.value=c.dataset.id,field.focus()})));const id=await showInputModal("ユーザーIDを入力","例: 4214303"),r2=document.getElementById("searchRecentRow");if(r2&&r2.remove(),!id)return;const num=String(id).replace(/[^0-9]/g,"");if(num){try{let h=JSON.parse(localStorage.getItem("koe_search_hist")||"[]");h=[num,...h.filter(x=>x!==num)].slice(0,10),localStorage.setItem("koe_search_hist",JSON.stringify(h))}catch(e){}viewProfile(parseInt(num,10))}else toast("数字のIDを入力してください","error")})}!function(){const c=document.querySelector(".callv2");if(c&&!document.getElementById("callMicMeter")){const m=document.createElement("div");m.id="callMicMeter",m.innerHTML='<svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor"><rect x="9" y="2.5" width="6" height="11" rx="3"/><path d="M6 11a6 6 0 0 0 12 0" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg><div class="mic-meter-track"><div id="callMicMeterFill"></div></div>';const bottom=c.querySelector(".callv2-bottom");bottom&&c.insertBefore(m,bottom)}}(),renderNavOrderEditor()}function showPage(name){document.getElementById("page-"+name)||(name="timeline");try{localStorage.setItem("koe_last_tab",name)}catch(e){}if("mypage"===name){setTimeout(function(){try{var el=document.getElementById("profileCommentDisplay");if(el&&el.textContent.trim()&&!el.classList.contains("bio-expanded"))koeApplyBioClamp("profileCommentDisplay")}catch(e){}},80)}"call"!==name&&(stopRoomListAutoRefresh(),currentRoomId||stopApplicantPolling()),document.querySelectorAll(".page").forEach(el=>el.classList.remove("active")),document.getElementById("page-"+name).classList.add("active"),document.querySelectorAll(".rail-item").forEach(el=>el.classList.toggle("active",el.dataset.view===name));const title=PAGE_TITLES[name]||name,topbar=document.getElementById("topbarTitle");topbar&&(topbar.textContent=title);"notifications"===name&&setTimeout(markNotifsSeen,250);const sidebarTitle=document.getElementById("sidebarTitle");sidebarTitle&&(sidebarTitle.textContent=title),document.getElementById("composeFab").style.display="timeline"===name?"flex":"none",(function(){var np=document.getElementById("newPostsPill");if(np&&"timeline"!==name)np.classList.remove("show");})(),"timeline"===name?loadTimeline():"call"===name?(loadGroupRooms(),loadModerationSettings(),startRoomListAutoRefresh(),currentRoomId&&startApplicantPolling()):"cheering"===name?loadReceivers("recommended"):"chat"===name?loadChats():"community"===name?(loadCommunities(),loadCommunityCategories()):"talk"===name?loadCallRecords():"notifications"===name?loadNotifications(currentNotifKind):"mypage"===name&&(loadProfile(),loadRoomHistory(),loadActivityHeatmap(),renderNavOrderEditor(),window.renderMutedUsers&&renderMutedUsers(),renderThemePresets(),renderAccounts())}function openComposeModal(){document.getElementById("composeText").value=function(){try{return localStorage.getItem("koe_draft")||""}catch(e){return""}}(),document.getElementById("composeTopic").value=function(){try{return localStorage.getItem("koe_last_topic")||"0"}catch(e){return"0"}}(),"function"==typeof clearComposeImage&&clearComposeImage(),document.getElementById("composeModal").style.display="flex",sfx("open"),setTimeout(()=>{const t=document.getElementById("composeText");t&&t.focus()},60),document.getElementById("composeText").focus()}function closeComposeModal(){{const pk=document.getElementById("emojiPicker");pk&&pk.remove()}document.getElementById("composeModal").style.display="none"}let composeImageDataUrl=null;function loadComposeImage(file){const reader=new FileReader;reader.onload=e=>{const img=new Image;img.onload=()=>{let w=img.width,h=img.height;if(w>1280||h>1280){const r=Math.min(1280/w,1280/h);w=Math.round(w*r),h=Math.round(h*r)}const canvas=document.createElement("canvas");canvas.width=w,canvas.height=h,canvas.getContext("2d").drawImage(img,0,0,w,h),composeImageDataUrl=canvas.toDataURL("image/jpeg",.88);const prev=document.getElementById("composeImagePreview");prev.src=composeImageDataUrl,prev.style.display="block",document.getElementById("composeImageName").textContent=file.name,document.getElementById("composeImageClear").style.display="inline"},img.src=e.target.result},reader.readAsDataURL(file)}function clearComposeImage(){composeImageDataUrl=null,document.getElementById("composeImageInput").value="",document.getElementById("composeImageName").textContent="未選択",document.getElementById("composeImagePreview").style.display="none",document.getElementById("composeImageClear").style.display="none"}let composeVoiceDataUrl=null,composeVoiceExt="webm",composeVoiceType="audio/webm",__mediaRecorder=null,__voiceChunks=[],__voiceTimer=null,__voiceStart=0;function __pickVoiceMime(){const cands=["audio/mp4","audio/webm;codecs=opus","audio/webm","audio/ogg;codecs=opus"];for(const c of cands)try{if(window.MediaRecorder&&MediaRecorder.isTypeSupported(c))return c}catch(e){}return""}async function toggleVoiceRecord(){const btn=document.getElementById("composeVoiceBtn"),status=document.getElementById("composeVoiceStatus");if(__mediaRecorder&&"recording"===__mediaRecorder.state)return void __mediaRecorder.stop();if(!navigator.mediaDevices||!navigator.mediaDevices.getUserMedia)return void toast("この端末では録音に対応していません","error");let stream;try{stream=await navigator.mediaDevices.getUserMedia({audio:!0})}catch(e){return void toast("マイクの使用を許可してください","error")}const mime=__pickVoiceMime();try{__mediaRecorder=mime?new MediaRecorder(stream,{mimeType:mime}):new MediaRecorder(stream)}catch(e){try{__mediaRecorder=new MediaRecorder(stream)}catch(e2){return void toast("録音を開始できませんでした","error")}}__voiceChunks=[],__mediaRecorder.ondataavailable=e=>{e.data&&e.data.size&&__voiceChunks.push(e.data)},__mediaRecorder.onstop=()=>{try{stream.getTracks().forEach(t=>t.stop())}catch(e){}__voiceTimer&&(clearInterval(__voiceTimer),__voiceTimer=null);const type=(__mediaRecorder.mimeType||mime||"audio/webm").split(";")[0],blob=new Blob(__voiceChunks,{type:type});composeVoiceType=type,composeVoiceExt=type.indexOf("mp4")>=0?"m4a":type.indexOf("ogg")>=0?"ogg":"webm";const reader=new FileReader;reader.onload=()=>{composeVoiceDataUrl=reader.result;const prev=document.getElementById("composeVoicePreview");prev&&(prev.src=composeVoiceDataUrl,prev.style.display="block");const c=document.getElementById("composeVoiceClear");c&&(c.style.display="inline")},reader.readAsDataURL(blob),btn&&(btn.innerHTML="<svg class=\"ico\" viewBox=\"0 0 24 24\" fill=\"currentColor\"><circle cx=\"12\" cy=\"12\" r=\"7\"/></svg> 再録音",btn.classList.remove("recording")),status&&(status.textContent="録音完了"),(function(){var pt=document.getElementById("composeVoiceProgressTrack");if(pt)pt.style.display="none";})()},__mediaRecorder.start(),__voiceStart=Date.now(),btn&&(btn.innerHTML="⏹ 停止 (0:00)",btn.classList.add("recording")),status&&(status.textContent="録音中…"),(function(){var pt=document.getElementById("composeVoiceProgressTrack"),pb=document.getElementById("composeVoiceProgressBar");if(pt)pt.style.display="block";if(pb)pb.style.width="0%";})(),__voiceTimer=setInterval(()=>{const sec=Math.floor((Date.now()-__voiceStart)/1e3),mm=Math.floor(sec/60),ss=String(sec%60).padStart(2,"0");btn&&(btn.innerHTML=`⏹ 停止 (${mm}:${ss})`),(function(){var pb=document.getElementById("composeVoiceProgressBar");if(pb)pb.style.width=Math.min(100,sec/90*100)+"%";})(),sec>=90&&__mediaRecorder&&"recording"===__mediaRecorder.state&&(__mediaRecorder.stop(),toast("録音は最大90秒です"))},500)}function clearComposeVoice(){composeVoiceDataUrl=null;const prev=document.getElementById("composeVoicePreview");prev&&(prev.src="",prev.style.display="none");const c=document.getElementById("composeVoiceClear");c&&(c.style.display="none");const status=document.getElementById("composeVoiceStatus");status&&(status.textContent="未録音");const btn=document.getElementById("composeVoiceBtn");btn&&(btn.innerHTML='<svg class="ico" viewBox="0 0 24 24" fill="currentColor"><circle cx="12" cy="12" r="7"/></svg> 録音');const pt=document.getElementById("composeVoiceProgressTrack"),pb=document.getElementById("composeVoiceProgressBar");if(pt)pt.style.display="none";if(pb)pb.style.width="0%";}async function submitComposePost(){const text=document.getElementById("composeText").value.trim(),topic=document.getElementById("composeTopic").value,isFeed=(String(topic)==="5");if(!text&&!composeImageDataUrl&&!composeVoiceDataUrl)return void toast("投稿内容・画像・音声のいずれかを入力してください","error");const btn=document.getElementById("composeSubmit");btn.disabled=!0,btn.classList.add("loading");const oldLabel=btn.textContent;let result;if(composeVoiceDataUrl?(btn.textContent="音声アップロード中...",result=await callApi(isFeed?"create_feed_post_with_voice":"create_timeline_post_with_voice",composeVoiceDataUrl,composeVoiceExt,composeVoiceType,text)):composeImageDataUrl?(btn.textContent="画像アップロード中...",result=await callApi(isFeed?"create_feed_post_with_image":"create_timeline_post_with_image",text,0,composeImageDataUrl)):result=await callApi(isFeed?"create_feed_post":"create_timeline_post",text,0),btn.disabled=!1,btn.classList.remove("loading"),btn.textContent=oldLabel,result.ok){var __img=composeImageDataUrl||"";var __text=text;clearComposeImage(),clearComposeVoice();try{localStorage.removeItem("koe_draft")}catch(e){}var __hu=document.getElementById("headerUser");var __optP={id:"temp"+Date.now(),user_id:typeof myUserId!=="undefined"&&myUserId?myUserId:currentAccountId(),name:__hu&&__hu.textContent||"あなた",icon_url:"",text:__text,image_url:__img,created_at:(new Date).toISOString(),likes:0,comments:0,bookmarked:false};closeComposeModal(),sfx("post"),toast("投稿しました");var __list=document.getElementById("timelineList");if(__list){try{__list.insertAdjacentHTML("afterbegin",postCardHtml(__optP))}catch(e){}}}else toast(("投稿失敗 (status:"+(result.status||"?")+" vsns:"+(result.vsns!==undefined?result.vsns:"?")+") "+koeErrMsg(result)),"error")}async function doLogin(){const email=document.getElementById("loginEmail").value.trim(),password=document.getElementById("loginPassword").value,errEl=document.getElementById("loginError"),btn=document.getElementById("loginBtn");if(errEl.textContent="",email&&password){btn.disabled=!0,btn.textContent="ログイン中...";try{const result=await api().login(email,password);if(result&&result.ok){try{window.__koeOnLoginSuccess&&window.__koeOnLoginSuccess(email,password)}catch(_e){}}result.ok?(window.__koeLoginMethod="mail",0!==result.user_id&&"0"!==result.user_id&&null!=result.user_id||(errEl.style.display="block",errEl.textContent="ログインはできましたが、アカウント情報を取得できませんでした。しばらく待ってからもう一度お試しください。解決しない場合は管理者に報告してください。"),enterMain(result.user_name)):(errEl.style.display="block",errEl.textContent=koeErrMsg(Object.assign({__method:"login"},result)),__koeAutoDiagOnFail())}finally{btn.disabled=!1,btn.textContent="ログイン"}}else errEl.textContent="メールアドレスとパスワードを入力してください"}function toggleAuthMode(){const loginFields=document.getElementById("loginFields"),signupFields=document.getElementById("signupFields"),toggle=document.getElementById("authModeToggle");document.getElementById("loginError").textContent="";const isLogin="none"!==loginFields.style.display;loginFields.style.display=isLogin?"none":"block",signupFields.style.display=isLogin?"block":"none",toggle.innerHTML=isLogin?'すでにアカウントをお持ちの方は<span id="switchToSignup">ログイン</span>':'アカウントをお持ちでない方は<span id="switchToSignup">新規登録</span>',document.getElementById("switchToSignup").addEventListener("click",toggleAuthMode)}async function doTokenLogin(){const token=document.getElementById("tokenLoginToken").value.trim(),uid=document.getElementById("tokenLoginUid").value.trim(),errEl=document.getElementById("loginError");if(errEl.style.display="none",!token)return errEl.textContent="トークンを入力してください",void(errEl.style.display="block");const btn=document.getElementById("tokenLoginBtn");btn.disabled=!0,btn.textContent="ログイン中...";try{
    // user_id が空なら token だけでログイン(api/account/session で自動解決)
    let result;
    if(uid){ result=await api().login_with_token(token,uid); if(!result.ok){ const r2=await api().login_token_only(token); if(r2&&r2.ok) result=r2; } }
    else { result=await api().login_token_only(token); }
    result.ok?(window.__koeLoginMethod="token",enterMain(result.user_name)):(errEl.textContent=(result.message||result.error||"トークンログインに失敗しました")+(result.raw?" / "+String(result.raw).slice(0,150):""),errEl.style.display="block")
  }catch(e){errEl.textContent="トークンログイン処理でエラー: "+(e&&e.message?e.message:String(e)),errEl.style.display="block"}finally{btn.disabled=!1,btn.textContent="トークンでログイン"}}async function showSessionToken(){const area=document.getElementById("tokenExportArea"),result=await api().export_token();result.ok?(area.value=`token: ${result.token}\nuser_id: ${result.user_id}`,area.style.display="block",area.select(),toast("トークンを表示しました。長押しでコピーできます")):toast("トークンがありません(未ログイン)","error")}async function doSignup(){const name=document.getElementById("signupName").value.trim(),email=document.getElementById("signupEmail").value.trim(),password=document.getElementById("signupPassword").value,sex=document.getElementById("signupSex").value,birthday=document.getElementById("signupBirthday").value.trim(),errEl=document.getElementById("loginError"),btn=document.getElementById("signupBtn");errEl.style.display="none";if(errEl.textContent="",name&&email&&password&&birthday){btn.disabled=!0,btn.textContent="登録中...";try{const result=await api().signup(email,password,name,parseInt(sex,10),birthday);result.ok?(window.__koeLoginMethod="mail",enterMain(result.user_name)):(errEl.style.display="block",errEl.textContent=(result.message||"登録に失敗しました")+(result.status?` (HTTP ${result.status})`:"")+` / 応答: ${JSON.stringify(result.raw||result.body||result.error||"").slice(0,300)}`,__koeAutoDiagOnFail())}catch(e){errEl.style.display="block";errEl.textContent="登録処理でエラーが発生しました: "+(e&&e.message?e.message:String(e))}finally{btn.disabled=!1,btn.textContent="新規登録"}}else{var __miss=[];if(!name)__miss.push("ニックネーム");if(!email)__miss.push("メールアドレス");if(!password)__miss.push("パスワード");if(!birthday)__miss.push("生年月日");errEl.style.display="block";errEl.textContent="未入力の項目があります: "+__miss.join("、")}}function enterMain(userName){try{sfx("success")}catch(e){}try{saveCurrentAccount()}catch(e){}document.getElementById("headerUser").textContent=userName||"",showScreen("mainScreen"),showPage(window.__koeStartTab?window.__koeStartTab():(function(){try{return localStorage.getItem("koe_last_tab")||"timeline"}catch(e){return"timeline"}})()),startLivePulse(),callApi("get_my_profile").then(r=>{r&&r.ok&&r.profile&&(myUserId=r.profile.user_id)}).catch(()=>{})}async function doLogout(){await showConfirmModal("ログアウトしますか?")&&(await api().logout(),livePulseTimer&&(clearInterval(livePulseTimer),livePulseTimer=null),document.getElementById("loginEmail").value="",document.getElementById("loginPassword").value="",document.getElementById("loginError").textContent="",showScreen("loginScreen"))}async function downloadVoice(url){if(url){if(window.AndroidApi&&window.AndroidApi.saveAudio)try{window.AndroidApi.saveAudio(url),toast("保存中…");try{haptic(10)}catch(e){}return}catch(e){}try{toast("ダウンロード中…");const res=await fetch(url),blob=await res.blob(),m=(url.split("?")[0].match(/\.[a-z0-9]{2,5}$/i)||[".m4a"])[0],a=document.createElement("a");a.href=URL.createObjectURL(blob),a.download="KoeTomo_"+Date.now()+m,document.body.appendChild(a),a.click(),setTimeout(()=>{try{URL.revokeObjectURL(a.href)}catch(e){}a.remove()},1500),toast("保存しました")}catch(e){toast("保存に失敗しました","error")}}}function showDlFormat(url){if(!url)return;window.__dlUrl=url;const m=document.getElementById("dlFormatSheet");m&&(m.style.display="flex")}async function doDownloadFmt(url,fmt){const m=document.getElementById("dlFormatSheet");if(m&&(m.style.display="none"),url)if("original"!==fmt)try{toast(fmt.toUpperCase()+"に変換中…(少し待ってね)");const res=await fetch(url),arr=await res.arrayBuffer(),ctx=new(window.AudioContext||window.webkitAudioContext);try{await ctx.resume()}catch(e){}const buf=await ctx.decodeAudioData(arr.slice(0));try{ctx.close()}catch(e){}if(buf.duration>600)return toast("音声が長いので元の形式で保存します","error"),void downloadVoice(url);let blob,ext;"wav"===fmt?(blob=audioBufferToWav(buf),ext="wav"):(await loadScript("vendor/lame.min.js"),blob=audioBufferToMp3(buf),ext="mp3"),await saveAudioBlob(blob,ext)}catch(e){toast("変換できないため元の形式で保存します","error"),downloadVoice(url)}else downloadVoice(url)}async function saveAudioBlob(blob,ext){if(window.AndroidApi&&window.AndroidApi.saveAudioData){const b64=await blobToBase64(blob);return void window.AndroidApi.saveAudioData(b64,ext)}const a=document.createElement("a");a.href=URL.createObjectURL(blob),a.download="KoeTomo_"+Date.now()+"."+ext,document.body.appendChild(a),a.click(),setTimeout(()=>{try{URL.revokeObjectURL(a.href)}catch(e){}a.remove()},1500),toast("保存しました")}function blobToBase64(blob){return new Promise((res,rej)=>{const r=new FileReader;r.onload=()=>res(String(r.result).split(",")[1]),r.onerror=rej,r.readAsDataURL(blob)})}function floatTo16(f){const o=new Int16Array(f.length);for(let i=0;i<f.length;i++){let s=Math.max(-1,Math.min(1,f[i]));o[i]=s<0?32768*s:32767*s}return o}function audioBufferToWav(buffer){const ch=buffer.numberOfChannels,sr=buffer.sampleRate,n=buffer.length,blockAlign=2*ch,dataLen=n*blockAlign,ab=new ArrayBuffer(44+dataLen),dv=new DataView(ab),ws=(o,str)=>{for(let i=0;i<str.length;i++)dv.setUint8(o+i,str.charCodeAt(i))};ws(0,"RIFF"),dv.setUint32(4,36+dataLen,!0),ws(8,"WAVE"),ws(12,"fmt "),dv.setUint32(16,16,!0),dv.setUint16(20,1,!0),dv.setUint16(22,ch,!0),dv.setUint32(24,sr,!0),dv.setUint32(28,sr*blockAlign,!0),dv.setUint16(32,blockAlign,!0),dv.setUint16(34,16,!0),ws(36,"data"),dv.setUint32(40,dataLen,!0);const chans=[];for(let c=0;c<ch;c++)chans.push(buffer.getChannelData(c));let off=44;for(let i=0;i<n;i++)for(let c=0;c<ch;c++){let s=Math.max(-1,Math.min(1,chans[c][i]));dv.setInt16(off,s<0?32768*s:32767*s,!0),off+=2}return new Blob([ab],{type:"audio/wav"})}function audioBufferToMp3(buffer){const ch=buffer.numberOfChannels>1?2:1,sr=buffer.sampleRate,enc=new lamejs.Mp3Encoder(ch,sr,128),l16=floatTo16(buffer.getChannelData(0)),r16=ch>1?floatTo16(buffer.getChannelData(1)):null,data=[];for(let i=0;i<l16.length;i+=1152){const lc=l16.subarray(i,i+1152),rc=r16?r16.subarray(i,i+1152):void 0,mp3=ch>1?enc.encodeBuffer(lc,rc):enc.encodeBuffer(lc);mp3.length&&data.push(new Int8Array(mp3))}const end=enc.flush();return end.length&&data.push(new Int8Array(end)),new Blob(data,{type:"audio/mpeg"})}async function openRecordComments(recordId){window.__recCommentId=recordId,document.getElementById("recordCommentModal").style.display="flex";const box=document.getElementById("recordCommentList");box.innerHTML=skeletonCards(2);const r=await callApi("get_record_comments",String(recordId));if(!r.ok)return void(box.innerHTML='<div class="empty-msg">読み込み失敗</div>');const cs=r.comments||[];cs.length?box.innerHTML=cs.map(c=>`<div class="pd-comment"><span class="pd-comment-av">${avatarHtml(c.name,c.icon_url)}</span><div class="pd-comment-main"><div class="pd-comment-name">${escapeHtml(c.name||"user "+c.user_id)} <span class="pd-comment-time">${relTime(c.created_at)}</span></div><div class="pd-comment-text">${escapeHtml(c.text)}</div></div></div>`).join(""):box.innerHTML='<div class="pd-empty">まだコメントはありません</div>'}async function postRecordCommentAction(){const inp=document.getElementById("recordCommentInput"),text=(inp.value||"").trim();if(!text||!window.__recCommentId)return;if((await callApi("post_record_comment",String(window.__recCommentId),text)).ok){inp.value="";try{sfx("post")}catch(e){}openRecordComments(window.__recCommentId)}else toast("送信に失敗しました","error")}async function loadFriends(){const box=document.getElementById("friendsList");if(!box)return;box.innerHTML=skeletonCards(3);const r=await callApi("get_friends_list","1");if(!r.ok)return void(box.innerHTML=`<div class="empty-msg">読み込めませんでした<br><button class="btn-secondary" style="width:auto;margin-top:8px;" onclick="reloadCurrentView()">再試行</button></div>`);const us=r.users||[];us.length?box.innerHTML=us.map(u=>`<div class="card" onclick='viewProfile(${u.user_id})'>${avatarHtml(u.name,u.icon_url)}<div class="card-body"><div class="card-name">${escapeHtml(u.name||"user "+u.user_id)} <span class="uid-tag">ID:${u.user_id}</span></div></div></div>`).join(""):box.innerHTML='<div class="empty-msg">相互フォローのユーザーはいません</div>'}let talkSource="others";async function loadCallRecords(source){source&&(talkSource=source),document.querySelectorAll(".talk-src-chip").forEach(c=>c.classList.toggle("active",c.dataset.talkSrc===talkSource));const box=document.getElementById("talkList");if(!box)return;box.innerHTML=skeletonCards(4);const r=await callApi("mine"===talkSource?"get_my_call_records":"get_call_records");if(!r.ok)return void(box.innerHTML=`<div class="empty-msg">読み込めませんでした<br><button class="btn-secondary" style="width:auto;margin-top:8px;" onclick="reloadCurrentView()">再試行</button><br><small style="opacity:.5;word-break:break-all;">${escapeHtml(r.raw||"")}</small></div>`);const recs=r.records||[];recs.length?(window.__callRecords=recs,box.innerHTML=recs.map((p,i)=>`\n    <div class="timeline-card${p.voice_url?" has-voice":""}${p.is_explicit?" is-regulated":""}" data-pid="${p.id}" data-likes="${p.likes||0}">\n      <div class="tl-head">\n        <span class="tl-avatar" onclick='viewProfile(${p.user_id})'>${avatarHtml(p.name,p.icon_url)}</span>\n        <div class="tl-meta" onclick='viewProfile(${p.user_id})'>\n          <div class="tl-name">${escapeHtml(p.name||"user "+p.user_id)}${p.other_name?` <span style="opacity:.55;font-weight:400;">↔ ${escapeHtml(p.other_name)}</span>`:""} <span class="uid-tag">ID:${p.user_id}</span></div>\n          <div class="tl-time" data-ts="${escapeHtml(p.created_at)}">${koeTimeLabel(p.created_at)}<span class="koe-tl-extra">${p.play_time?" ・ 通話"+__fmtT(p.play_time):""}${p.play_count?" ・ ▶"+p.play_count:""}</span></div>\n        </div>\n      </div>\n      ${p.text?`<div class="tl-text">${linkify(p.text)}</div>`:""}\n      ${p.voice_url?voicePlayerHtml(p.voice_url):'<div class="empty-msg" style="font-size:12px;padding:4px 0;">(音声を取得できませんでした)</div>'}\n      <div class="tl-actions">\n        <span class="like-btn ${p.liked?"liked":""}" data-rec-like="${i}">\n          ${p.liked?'<svg class="ico" viewBox="0 0 24 24" fill="currentColor" style="color:#ff5a6a"><path d="M12 20.3 4.6 13c-2-2-2-5.2 0-7.1 1.9-1.8 4.9-1.6 6.7.3l.7.8.7-.8c1.8-1.9 4.8-2.1 6.7-.3 2 1.9 2 5.1 0 7.1L12 20.3Z"/></svg>':'<svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 20.3 4.6 13c-2-2-2-5.2 0-7.1 1.9-1.8 4.9-1.6 6.7.3l.7.8.7-.8c1.8-1.9 4.8-2.1 6.7-.3 2 1.9 2 5.1 0 7.1L12 20.3Z"/></svg>'} <span class="rec-like-n">${p.likes}</span>\n        </span>\n        <span class="comment-btn" data-rec-comment="${i}" style="cursor:pointer;"><svg class="ico" viewBox="0 0 24 24" fill="currentColor"><path d="M4 5.5A2.5 2.5 0 0 1 6.5 3h11A2.5 2.5 0 0 1 20 5.5v8A2.5 2.5 0 0 1 17.5 16H9l-4 3.6a.8.8 0 0 1-1.3-.6V5.5Z"/></svg> ${p.comments}</span>\n      </div>\n    </div>`).join(""),box.querySelectorAll("[data-rec-comment]").forEach(el=>{el.addEventListener("click",()=>{const rec=window.__callRecords[+el.dataset.recComment];rec&&openRecordComments(rec.id)})}),box.querySelectorAll("[data-rec-like]").forEach(el=>{el.addEventListener("click",async()=>{const rec=window.__callRecords[+el.dataset.recLike];if(!rec)return;const was=rec.liked;rec.liked=!was,rec.likes+=was?-1:1,el.classList.toggle("liked",rec.liked);const n=el.querySelector(".rec-like-n");n&&(n.textContent=rec.likes);try{sfx(rec.liked?"like":"tap")}catch(e){}(await callApi("toggle_record_like",String(rec.id),was)).ok||(rec.liked=was,rec.likes+=was?1:-1,el.classList.toggle("liked",rec.liked),n&&(n.textContent=rec.likes),toast("失敗しました","error"))})})):box.innerHTML=`<div class="empty-msg">${"mine"===talkSource?"あなたの録音はありません":"トークがありません"}<br><small style="opacity:.5;word-break:break-all;">${escapeHtml(r.raw||"")}</small></div>`}const __PLAY_SVG='<svg viewBox="0 0 24 24" fill="currentColor"><path d="M8 5v14l11-7z"/></svg>',__PAUSE_SVG='<svg viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="5" width="4" height="14" rx="1"/><rect x="14" y="5" width="4" height="14" rx="1"/></svg>';function __fmtT(s){s=Math.floor(s||0);return Math.floor(s/60)+":"+String(s%60).padStart(2,"0")}const VOICE_BADGE='<span class="voice-badge"><svg viewBox="0 0 24 24" fill="currentColor"><rect x="9" y="2.5" width="6" height="11" rx="3"/><path d="M6 11a6 6 0 0 0 12 0" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/><rect x="11" y="18" width="2" height="3.4" rx="1"/></svg> ボイス</span>';function voicePlayerHtml(url){return`<div class="aplayer" data-src="${escAttr(url)}">\n    <button class="aplayer-btn" aria-label="再生">${__PLAY_SVG}</button>\n    <div class="aplayer-bar"><div class="aplayer-fill"></div></div>\n    <span class="aplayer-time">0:00</span>\n    <button class="voice-dl" data-voice-url="${escAttr(url)}" title="音声を保存"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3v12M7 10l5 5 5-5M5 20h14"/></svg></button>\n  </div>`}let __curAudio=null,__curPlayer=null;function __setPlay(pl,playing){const b=pl&&pl.querySelector(".aplayer-btn");b&&(b.innerHTML=playing?__PAUSE_SVG:__PLAY_SVG)}function toggleAudioPlayer(pl){if(!pl)return;if(__curPlayer===pl&&__curAudio)return void(__curAudio.paused?__curAudio.play().catch(()=>{}):__curAudio.pause());if(__curAudio){try{__curAudio.pause()}catch(e){}__setPlay(__curPlayer,!1)}__curPlayer=pl,__curAudio=new Audio(pl.dataset.src);const fill=pl.querySelector(".aplayer-fill"),time=pl.querySelector(".aplayer-time");__curAudio.ontimeupdate=()=>{const d=__curAudio.duration||0,c=__curAudio.currentTime||0;fill&&(fill.style.width=d?c/d*100+"%":"0%"),time&&(time.textContent=__fmtT(c)+(d?" / "+__fmtT(d):""))},__curAudio.onloadedmetadata=()=>{const d=__curAudio.duration||0;time&&d&&(time.textContent="0:00 / "+__fmtT(d))},__curAudio.onended=()=>{__setPlay(pl,!1),fill&&(fill.style.width="0%");try{if(localStorage.getItem("koe_voice_continuous")==="1"){var players=Array.prototype.slice.call(document.querySelectorAll(".aplayer"));var idx=players.indexOf(pl);if(idx>=0&&idx<players.length-1){var next=players[idx+1];setTimeout(function(){toggleAudioPlayer(next);try{next.scrollIntoView({block:"center",behavior:"smooth"})}catch(e){}},400)}}}catch(e){}},__curAudio.onpause=()=>__setPlay(pl,!1),__curAudio.onplay=()=>__setPlay(pl,!0),__curAudio.onerror=()=>{__setPlay(pl,!1),toast("再生できませんでした(音声URLが正しくない可能性)","error")},__curAudio.play().catch(()=>{toast("再生できませんでした","error")})}function postCardHtml(p){return`\n    <div class="timeline-card${p.voice_url?" has-voice":""}${p.is_explicit?" is-regulated":""}" data-pid="${p.id}" data-likes="${p.likes||0}">\n      <div class="tl-head">\n        <span class="tl-avatar" onclick='viewProfile(${p.user_id})'>${avatarHtml(p.name,p.icon_url)}</span>\n        <div class="tl-meta" onclick='viewProfile(${p.user_id})'>\n          <div class="tl-name">${escapeHtml(p.name)} <span class="uid-tag">ID:${p.user_id}</span>${koeSpamTag(p)}${p.is_talk?' <span class="uid-tag koe-feedbadge">通話募集</span>':""}${p.is_explicit?' <span class="uid-tag koe-regbadge">⚠ 規制対象</span>':""}</div>\n          <div class="tl-time" data-ts="${escapeHtml(p.created_at)}" title="${escapeHtml(p.created_at)}">${koeTimeLabel(p.created_at)}</div>\n        </div>\n      </div>\n      ${p.text?`<div class="tl-text">${linkify(p.text)}</div>`:""}\n      ${p.image_url?`<img class="post-image" loading="lazy" decoding="async" src="${escAttr(p.image_url)}" onclick='event.stopPropagation(); openLightbox(${escAttr(JSON.stringify(p.image_url||""))})' onerror="this.style.display='none'">`:""}\n      ${p.voice_url?VOICE_BADGE+voicePlayerHtml(p.voice_url):""}\n      <div class="tl-actions">\n        <span class="comment-btn" onclick='openPostDetail(event, ${p.id})'><svg class="ico" viewBox="0 0 24 24" fill="currentColor"><path d="M4 5.5A2.5 2.5 0 0 1 6.5 3h11A2.5 2.5 0 0 1 20 5.5v8A2.5 2.5 0 0 1 17.5 16H9l-4 3.6a.8.8 0 0 1-1.3-.6V5.5Z"/></svg> ${p.comments}</span>\n        <span class="like-btn ${postLiked(p)?"liked":""}" onclick='toggleTimelineLike(event, ${p.id}, ${postLiked(p)})'>\n          ${postLiked(p)?'<svg class="ico" viewBox="0 0 24 24" fill="currentColor" style="color:#ff5a6a"><path d="M12 20.3 4.6 13c-2-2-2-5.2 0-7.1 1.9-1.8 4.9-1.6 6.7.3l.7.8.7-.8c1.8-1.9 4.8-2.1 6.7-.3 2 1.9 2 5.1 0 7.1L12 20.3Z"/></svg>':'<svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 20.3 4.6 13c-2-2-2-5.2 0-7.1 1.9-1.8 4.9-1.6 6.7.3l.7.8.7-.8c1.8-1.9 4.8-2.1 6.7-.3 2 1.9 2 5.1 0 7.1L12 20.3Z"/></svg>'} ${Math.max(p.likes||0,postLiked(p)?1:0)}\n        </span>\n        <span class="bookmark-btn ${(p.bookmarked||koeBmHas(p.id))?"marked":""}" onclick='toggleBookmark(event, ${p.id}, ${!!(p.bookmarked||koeBmHas(p.id))}, ${p.is_talk?1:0})' title="ブックマーク"><svg class="ico" viewBox="0 0 24 24" fill="currentColor"><path d="M6 3.5h12a1 1 0 0 1 1 1V21l-7-4-7 4V4.5a1 1 0 0 1 1-1Z"/></svg></span>\n        ${p.user_id===myUserId?`<span class="report-btn" onclick='deleteOwnPost(event, ${p.id}, ${p.is_talk?1:0})'><svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13"/></svg> 削除</span>`:`<span class="report-btn" onclick='promptTimelineReport(event, ${p.user_id})'><svg class="ico" viewBox="0 0 24 24" fill="currentColor"><rect x="4" y="3" width="1.8" height="18" rx=".9"/><path d="M6 4h11l-2 3 2 3H6V4Z"/></svg> 通報</span>`}\n      </div>\n    </div>`}async function loadBookmarks(){const box=document.getElementById("bookmarksList");if(!box)return;box.innerHTML=skeletonCards(3);const result=await callApi("get_bookmarks");if(!result.ok)return void(box.innerHTML='<div class="empty-msg">読み込み失敗</div>');const posts=result.posts||[];posts.length?box.innerHTML=posts.map(postCardHtml).join(""):box.innerHTML='<div class="empty-msg">ブックマークした投稿はありません</div>'}function refreshCurrentPage(){var active=document.querySelector(".page.active");var id=active?active.id:"";try{if(id==="page-timeline"){timelineMaxId="";return loadTimeline(false)}if(id==="page-call"&&typeof loadGroupRooms==="function")return loadGroupRooms();if(id==="page-chat"&&typeof loadChats==="function")return loadChats();if(id==="page-notifications"&&typeof loadNotifications==="function")return loadNotifications(typeof currentNotifKind!=="undefined"?currentNotifKind:"normal");if(id==="page-mypage"&&typeof loadProfile==="function")return loadProfile();timelineMaxId="";return loadTimeline(false)}catch(e){}}function initPullToRefresh(){var cb=document.getElementById("contentBody");if(!cb||cb.__ptrBound)return;if(cb.__ptr||document.querySelector(".content-body.__x")||true){cb.__ptrBound=true;return} /* setupPullToRefresh と二重に反応して2回更新されるため無効化 */cb.__ptrBound=true;var startY=0,pulling=false,dist=0,ready=false;cb.addEventListener("touchstart",function(e){pulling=cb.scrollTop<=0;startY=pulling?e.touches[0].clientY:0;dist=0;ready=false},{passive:true});cb.addEventListener("touchmove",function(e){if(!pulling)return;dist=e.touches[0].clientY-startY;if(dist>0&&cb.scrollTop<=0){ready=dist>80;cb.style.transition="none";cb.style.transform="translateY("+Math.min(dist*.4,52)+"px)";if(dist>12&&e.cancelable)e.preventDefault()}},{passive:false});cb.addEventListener("touchend",function(){if(!pulling)return;pulling=false;cb.style.transition="transform .25s";cb.style.transform="";if(ready){try{toast(" 更新中…")}catch(e){}try{sfx("refresh")}catch(e){}try{refreshCurrentPage()}catch(e){}}ready=false;dist=0})}function initTimelineFilters(){document.querySelectorAll(".timeline-feed-chip[data-media]").forEach(function(chip){chip.addEventListener("click",function(){var m=chip.dataset.media;window.__tlMedia=window.__tlMedia===m?"":m;document.querySelectorAll(".timeline-feed-chip[data-media]").forEach(function(c){c.classList.toggle("active",c.dataset.media===window.__tlMedia)});timelineMaxId="";var l=document.getElementById("timelineList");if(l)l.innerHTML="";loadTimeline(false);try{sfx("tab")}catch(e){}})})}function initPullToRefresh(){var cb=document.getElementById("contentBody");if(!cb||cb.__ptrBound)return;if(cb.__ptr||document.querySelector(".content-body.__x")||true){cb.__ptrBound=true;return} /* setupPullToRefresh と二重に反応して2回更新されるため無効化 */cb.__ptrBound=true;var startY=0,pulling=false,dist=0,ready=false;cb.addEventListener("touchstart",function(e){pulling=cb.scrollTop<=0;startY=pulling?e.touches[0].clientY:0;dist=0;ready=false},{passive:true});cb.addEventListener("touchmove",function(e){if(!pulling)return;dist=e.touches[0].clientY-startY;if(dist>0&&cb.scrollTop<=0){ready=dist>80;cb.style.transition="none";cb.style.transform="translateY("+Math.min(dist*.4,52)+"px)";if(dist>12&&e.cancelable)e.preventDefault()}},{passive:false});cb.addEventListener("touchend",function(){if(!pulling)return;pulling=false;cb.style.transition="transform .25s";cb.style.transform="";if(ready){try{toast(" 更新中…")}catch(e){}try{sfx("refresh")}catch(e){}try{refreshCurrentPage()}catch(e){}}ready=false;dist=0})}function initTimelineFilters(){document.querySelectorAll(".timeline-feed-chip[data-media]").forEach(function(chip){chip.addEventListener("click",function(){var m=chip.dataset.media;window.__tlMedia=window.__tlMedia===m?"":m;document.querySelectorAll(".timeline-feed-chip[data-media]").forEach(function(c){c.classList.toggle("active",c.dataset.media===window.__tlMedia)});timelineMaxId="";var l=document.getElementById("timelineList");if(l)l.innerHTML="";loadTimeline(false);try{sfx("tab")}catch(e){}})})}function sortTimelineDom(){var list=document.getElementById("timelineList");if(!list)return;var lm=document.getElementById("timelineLoadMoreRow");var cards=Array.prototype.slice.call(list.querySelectorAll(".timeline-card"));if(window.__tlSort==="popular"){cards.sort(function(a,b){return parseInt(b.getAttribute("data-likes")||"0",10)-parseInt(a.getAttribute("data-likes")||"0",10)});cards.forEach(function(c){list.appendChild(c)});if(lm)list.appendChild(lm)}}function __tlCacheSave(feed,html){try{if(("all"===feed||"following"===feed)&&html&&html.length<3e5)localStorage.setItem("koe_tlc_"+feed,html)}catch(e){}}function __tlCacheGet(feed){try{return"all"===feed||"following"===feed?localStorage.getItem("koe_tlc_"+feed):null}catch(e){return null}}async function loadTimeline(append){const list=document.getElementById("timelineList");if(!append){list.children.length>0&&!list.querySelector(".skeleton-card")&&!list.querySelector(".empty-msg")||(list.innerHTML=__tlCacheGet(timelineFeed)||skeletonCards(4)),timelineMaxId="",window.__tlSeenReset&&window.__tlSeenReset()}const slowTimer=setTimeout(()=>setTimelineSlow(!0),3e3);let result;const __feedAtStart=timelineFeed,__seq=(window.__tlSeq=(window.__tlSeq||0)+1);try{const method="following"===timelineFeed?"get_following_timeline":"bookmark"===timelineFeed?"get_bookmarks":"feed"===timelineFeed?"get_feed_timeline":"get_timeline";result=await callApi(method,timelineMaxId||"")}finally{clearTimeout(slowTimer),setTimelineSlow(!1)}
/* 取得中にフィードが切り替わった／新しい読み込みが始まった場合は古い結果を捨てる(別フィードの投稿が混ざる・カーソル/キャッシュの取り違え防止) */
if(__feedAtStart!==timelineFeed||(!append&&__seq!==window.__tlSeq))return;if(!result.ok)return void(append?toast("追加読み込みに失敗しました","error"):list.innerHTML='<div class="empty-msg">読み込みに失敗しました（サーバーが混雑している可能性があります）。<br><button class="btn-secondary" style="margin-top:10px;" onclick="loadTimeline()">再試行</button></div>');if(document.getElementById("timelineLoadMoreRow")?.remove(),!result.posts.length)return void(append||(list.innerHTML='<div class="empty-msg">まだ投稿がありません<br><button class="btn-primary" style="margin-top:12px;width:auto;" onclick="openComposeModal()">最初の投稿をする</button></div>'));try{koeAutoDeleteRegulated(result.posts)}catch(e){}const __fpreview=result.posts.filter(p=>!isFilteredPost(p));if(!append&&!__fpreview.length)return void(list.innerHTML='<div class="empty-msg">表示できる投稿がありません(フィルターで全て非表示)</div>');try{koeRememberPosts(result.posts)}catch(e){}if(append){try{var __seenIds={};list.querySelectorAll(".timeline-card[data-pid]").forEach(function(el){__seenIds[el.getAttribute("data-pid")]=1});result.posts=result.posts.filter(function(p){return !__seenIds[String(p.id)]})}catch(e){}}const html=result.posts.filter(p=>!isFilteredPost(p)&&(!window.__tlKeep||window.__tlKeep(p.id,append))).map(p=>`\n    <div class="timeline-card${p.voice_url?" has-voice":""}${p.is_explicit?" is-regulated":""}" data-pid="${p.id}" data-likes="${p.likes||0}">\n      <div class="tl-head">\n        <span class="tl-avatar" onclick='viewProfile(${p.user_id})'>${avatarHtml(p.name,p.icon_url)}</span>\n        <div class="tl-meta" onclick='viewProfile(${p.user_id})'>\n          <div class="tl-name">${escapeHtml(p.name)} <span class="uid-tag">ID:${p.user_id}</span>${koeSpamTag(p)}${p.is_talk?' <span class="uid-tag koe-feedbadge">通話募集</span>':""}${p.is_explicit?' <span class="uid-tag koe-regbadge">⚠ 規制対象</span>':""}</div>\n          <div class="tl-time" data-ts="${escapeHtml(p.created_at)}" title="${escapeHtml(p.created_at)}">${koeTimeLabel(p.created_at)}</div>\n        </div>\n      </div>\n      ${p.text?`<div class="tl-text">${linkify(p.text)}</div>`:""}\n      ${p.image_url?`<img class="post-image" loading="lazy" decoding="async" src="${escAttr(p.image_url)}" onclick='event.stopPropagation(); openLightbox(${escAttr(JSON.stringify(p.image_url||""))})' onerror="this.style.display='none'">`:""}\n      ${p.voice_url?VOICE_BADGE+voicePlayerHtml(p.voice_url):""}\n      <div class="tl-actions">\n        <span class="comment-btn" onclick='openPostDetail(event, ${p.id})'><svg class="ico" viewBox="0 0 24 24" fill="currentColor"><path d="M4 5.5A2.5 2.5 0 0 1 6.5 3h11A2.5 2.5 0 0 1 20 5.5v8A2.5 2.5 0 0 1 17.5 16H9l-4 3.6a.8.8 0 0 1-1.3-.6V5.5Z"/></svg> ${p.comments}</span>\n        <span class="like-btn ${postLiked(p)?"liked":""}" onclick='toggleTimelineLike(event, ${p.id}, ${postLiked(p)})'>\n          ${postLiked(p)?'<svg class="ico" viewBox="0 0 24 24" fill="currentColor" style="color:#ff5a6a"><path d="M12 20.3 4.6 13c-2-2-2-5.2 0-7.1 1.9-1.8 4.9-1.6 6.7.3l.7.8.7-.8c1.8-1.9 4.8-2.1 6.7-.3 2 1.9 2 5.1 0 7.1L12 20.3Z"/></svg>':'<svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 20.3 4.6 13c-2-2-2-5.2 0-7.1 1.9-1.8 4.9-1.6 6.7.3l.7.8.7-.8c1.8-1.9 4.8-2.1 6.7-.3 2 1.9 2 5.1 0 7.1L12 20.3Z"/></svg>'} ${Math.max(p.likes||0,postLiked(p)?1:0)}\n        </span>\n        <span class="bookmark-btn ${(p.bookmarked||koeBmHas(p.id))?"marked":""}" onclick='toggleBookmark(event, ${p.id}, ${!!(p.bookmarked||koeBmHas(p.id))}, ${p.is_talk?1:0})' title="ブックマーク"><svg class="ico" viewBox="0 0 24 24" fill="currentColor"><path d="M6 3.5h12a1 1 0 0 1 1 1V21l-7-4-7 4V4.5a1 1 0 0 1 1-1Z"/></svg></span>\n        ${p.user_id===myUserId?`<span class="report-btn" onclick='deleteOwnPost(event, ${p.id}, ${p.is_talk?1:0})'><svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13"/></svg> 削除</span>`:`<span class="report-btn" onclick='promptTimelineReport(event, ${p.user_id})'><svg class="ico" viewBox="0 0 24 24" fill="currentColor"><rect x="4" y="3" width="1.8" height="18" rx=".9"/><path d="M6 4h11l-2 3 2 3H6V4Z"/></svg> 通報</span>`}\n      </div>\n    </div>\n  `).join("");append?list.insertAdjacentHTML("beforeend",html):(list.innerHTML=html,__tlCacheSave(timelineFeed,html)),(function(){var __nx=result.next_max_id||"";/* 同じカーソルが返る／投稿0件なら終端扱い(無限ループ防止) */if(append&&(__nx===timelineMaxId||!(result.posts&&result.posts.length)))__nx="";timelineMaxId=__nx})(),!append&&result.posts&&result.posts.length&&(window.__newestPostId=result.posts[0].id),timelineMaxId&&list.insertAdjacentHTML("beforeend",'<div id="timelineLoadMoreRow" style="padding:8px 0;">\n         <button class="btn-secondary" onclick="loadTimeline(true)">もっと読む</button>\n       </div>')}let timelineMaxId="",timelineFeed="all",myUserId=null;function switchTimelineFeed(feed){feed&&feed!==timelineFeed&&(timelineFeed=feed,timelineMaxId="",document.querySelectorAll(".timeline-feed-chip[data-feed]").forEach(c=>c.classList.toggle("active",c.dataset.feed===feed)),document.getElementById("timelineList").innerHTML="",loadTimeline(!1))}function setTimelineSlow(on){const list=document.getElementById("timelineList");let el=document.getElementById("timelineSlowNotice");on?!el&&list&&(el=document.createElement("div"),el.id="timelineSlowNotice",el.className="timeline-slow-notice",el.innerHTML='<span class="joining-spinner"></span> サーバー応答待ち… 混雑している可能性があります',list.parentElement.insertBefore(el,list)):el&&el.remove()}function koeBmLocal(){try{return JSON.parse(localStorage.getItem("koe_bm_local")||"[]")}catch(e){return[]}}function koeBmSet(id,on){try{var a=koeBmLocal().map(String).filter(function(x){return x!==String(id)});if(on)a.push(String(id));localStorage.setItem("koe_bm_local",JSON.stringify(a.slice(-500)))}catch(e){}}function koeBmHas(id){try{return koeBmLocal().indexOf(String(id))>=0}catch(e){return false}}
async function toggleBookmark(evt,postId,currentlyBookmarked,isTalk){evt.stopPropagation();const result=await callApi("toggle_timeline_bookmark",postId,!!currentlyBookmarked,isTalk?1:0);result.ok?(koeBmSet(postId,!currentlyBookmarked),sfx(currentlyBookmarked?"unlike":"bookmark"),toast(currentlyBookmarked?"ブックマークを外しました":"ブックマークしました"),loadTimeline(!1)):toast(`ブックマーク失敗: ${koeErrMsg(result)}`.slice(0,120),"error")}const HEART_F_SVG='<svg class="ico" viewBox="0 0 24 24" fill="currentColor" style="color:#ff5a6a"><path d="M12 20.3 4.6 13c-2-2-2-5.2 0-7.1 1.9-1.8 4.9-1.6 6.7.3l.7.8.7-.8c1.8-1.9 4.8-2.1 6.7-.3 2 1.9 2 5.1 0 7.1L12 20.3Z"/></svg>',HEART_O_SVG='<svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 20.3 4.6 13c-2-2-2-5.2 0-7.1 1.9-1.8 4.9-1.6 6.7.3l.7.8.7-.8c1.8-1.9 4.8-2.1 6.7-.3 2 1.9 2 5.1 0 7.1L12 20.3Z"/></svg>';async function toggleTimelineLike(evt,postId,currentlyLiked){evt.stopPropagation(),haptic(12);const span=evt.currentTarget,willLike=!currentlyLiked;markLiked(postId,willLike);try{const m=(span.textContent||"").trim().match(/(\d+)\s*$/);let n=Math.max(0,(m?parseInt(m[1],10):0)+(willLike?1:-1));if(span.classList.toggle("liked",willLike),span.innerHTML=(willLike?HEART_F_SVG:HEART_O_SVG)+" "+n,span.setAttribute("onclick",`toggleTimelineLike(event, ${postId}, ${willLike})`),willLike){span.classList.remove("like-pop"),span.offsetWidth,span.classList.add("like-pop");try{const r=span.getBoundingClientRect();heartBurst(r.left+r.width/2,r.top)}catch(e){}sfx("like")}else sfx("unlike")}catch(e){}const r=await callApi("toggle_timeline_like",postId,currentlyLiked);r&&r.ok||(markLiked(postId,currentlyLiked),toast("いいねに失敗しました","error"),await loadTimeline())}let __pdPostId=null;async function openPostDetail(evt,postId){evt&&evt.stopPropagation();const _pdM=document.getElementById("postDetailModal"),_pdWasOpen=_pdM&&_pdM.style.display==="flex";__pdPostId=postId,_pdM.style.display="flex",sfx("open");{const mb=document.querySelector("#postDetailModal .modal-body");mb&&!_pdWasOpen&&(mb.scrollTop=0)}{const pdPost=document.getElementById("pdPost");pdPost&&(pdPost.innerHTML='<div class="pd-empty">読み込み中…</div>',callApi("get_feed_post",String(postId)).then(pr=>{if(pr&&pr.ok&&pr.post){const pp=pr.post;pdPost.innerHTML='<div class="pd-post-head"'+(pp.user_id?' onclick="event.stopPropagation();viewProfile('+parseInt(pp.user_id,10)+')" style="cursor:pointer;" title="プロフィールを見る"':'')+'>'+avatarHtml(pp.name,pp.icon_url)+'<div class="pd-post-meta"><div class="pd-post-name">'+escapeHtml(pp.name||"")+'</div><div class="pd-post-time">'+escapeHtml(relTime(pp.created_at)||"")+"</div></div></div>"+(pp.text?'<div class="pd-post-text">'+escapeHtml(pp.text)+"</div>":"")+(pp.image_url?'<img class="pd-post-img" src="'+escAttr(pp.image_url)+'" onerror="this.remove()">':"")+'<div class="pd-post-stats"><span class="pd-stat">♥ '+(pp.likes||0)+'</span><span class="pd-stat"> '+(pp.comments||0)+"</span></div>"+(pp.text||pp.image_url||pp.user_id&&0!==Number(pp.user_id)?"":'<div class="pd-empty" style="word-break:break-all;font-size:11px;opacity:.5;margin-top:6px;">'+escapeHtml(pr.raw||"")+"</div>")}else{var __c=(pr&&Number(pr.status)===404)?koeMarkDeleted(postId):null;if(__c){pdPost.innerHTML='<div class="pd-post-head"'+(__c.u?' onclick="event.stopPropagation();viewProfile('+Number(__c.u)+')" style="cursor:pointer;"':'')+'>'+avatarHtml(__c.n,__c.i)+'<div class="pd-post-meta"><div class="pd-post-name">'+escapeHtml(__c.n||"")+' <span class="uid-tag koe-regbadge">削除された投稿</span></div><div class="pd-post-time">'+escapeHtml(koeTimeLabel(__c.c).replace(/<[^>]+>/g," ")||"")+'</div></div></div>'+(__c.t?'<div class="pd-post-text">'+escapeHtml(__c.t)+'</div>':'')+(__c.img?'<img class="pd-post-img" src="'+escAttr(__c.img)+'" onerror="this.remove()">':'')+'<div class="card-sub" style="opacity:.6;margin-top:6px;">この投稿は削除されています。表示したときの内容をこの端末の記録から表示しています（マイページ「削除された投稿」にも残ります）。</div>'}else if(pr&&Number(pr.status)===404){pdPost.innerHTML='<div class="pd-empty">この投稿は削除されています</div>'}else pdPost.innerHTML=""}}).catch(()=>{pdPost.innerHTML=""}))}const likers=document.getElementById("pdLikers"),comments=document.getElementById("pdComments");likers.innerHTML='<span class="pd-empty">読み込み中…</span>',comments.innerHTML='<span class="pd-empty">読み込み中…</span>',document.getElementById("pdLikeCount").textContent="";try{const r=await callApi("get_timeline_likers",postId);r.ok&&(r.users||[]).length?(document.getElementById("pdLikeCount").textContent=`(${r.users.length})`,likers.innerHTML=r.users.map(u=>`<span class="pd-liker" onclick="viewProfile(${u.user_id})" style="cursor:pointer;" title="プロフィールを見る">${avatarHtml(u.name,u.icon_url)}${escapeHtml(u.name)}</span>`).join("")):likers.innerHTML=`<span class="pd-empty">まだいません${r.ok?"":" ("+escapeHtml(r.raw||"")+")"}</span>`}catch(e){likers.innerHTML='<span class="pd-empty">取得失敗</span>'}try{window.__pdComments=[];window.__pdCommentPage=1;window.__pdCommentsDone=false;window.__pdCommentsLoading=false;
      const r=await callApi("get_timeline_comments",postId,"1");
      if(r.ok&&(r.comments||[]).length){window.__pdComments=r.comments.slice();comments.innerHTML=window.__pdComments.map(pdCommentHtml).join("")+pdMoreBtnHtml();}
      else{window.__pdCommentsDone=true;comments.innerHTML=`<span class="pd-empty">まだ返信はありません${r.ok?"":" ("+escapeHtml(r.raw||"")+")"}</span>`;}
      try{__pdWireCommentScroll(postId);}catch(_e){}
      try{setTimeout(function(){try{__pdFillIfNeeded();}catch(e){}},120);}catch(_e){}
    }catch(e){comments.innerHTML='<span class="pd-empty">取得失敗</span>'}}
function pdMoreBtnHtml(){ return window.__pdCommentsDone ? "" : '<div id="pdCommentsSentinel" style="height:1px;"></div>'; }
window.__pdLoadMoreComments = async function(postId){
  if(window.__pdCommentsLoading||window.__pdCommentsDone)return;
  window.__pdCommentsLoading=true;
  try{
    var next=(window.__pdCommentPage||1)+1;
    var r=await callApi("get_timeline_comments",String(postId),String(next));
    if(r&&r.ok&&(r.comments||[]).length){
      // 既存と重複しないものだけ追加(サーバが同じページを返す場合の保険)
      var have={}; (window.__pdComments||[]).forEach(function(c){ have[(c.user_id||"")+"|"+(c.created_at||"")+"|"+(c.text||"")]=1; });
      var fresh=r.comments.filter(function(c){ return !have[(c.user_id||"")+"|"+(c.created_at||"")+"|"+(c.text||"")]; });
      window.__pdCommentPage=next;
      if(fresh.length===0){ window.__pdCommentsDone=true; }
      else{
        window.__pdComments=(window.__pdComments||[]).concat(fresh);
        var el=document.getElementById("pdComments");
        if(el){el.innerHTML=window.__pdComments.map(pdCommentHtml).join("")+pdMoreBtnHtml();}
      }
    }else{ window.__pdCommentsDone=true; }
  }catch(e){}
  window.__pdCommentsLoading=false;
  var el2=document.getElementById("pdComments");
  if(window.__pdCommentsDone){ var b=document.getElementById("pdMoreComments"); if(b)b.remove(); var sn=document.getElementById("pdCommentsSentinel"); if(sn)sn.remove(); }
  else{ try{__pdObserveSentinel();}catch(e){} try{__pdFillIfNeeded();}catch(e){} }
};
// モーダルがまだスクロール不能(内容が少ない)なら、埋まるまで自動で続きを読む
async function __pdFillIfNeeded(){
  try{
    if(window.__pdCommentsDone||window.__pdCommentsLoading)return;
    var sc=document.getElementById("pdComments");
    if(!sc)return;
    // 返信枠がまだスクロール不能(内容が少ない)なら、埋まるまで自動で続きを読む
    if(sc.scrollHeight<=sc.clientHeight+20){
      await window.__pdLoadMoreComments(window.__pdPostId||__pdPostId);
    }
  }catch(e){}
}
document.addEventListener("click",function(ev){ var t=ev.target; if(t&&t.id==="pdMoreComments"){ ev.preventDefault(); window.__pdLoadMoreComments(window.__pdPostId||__pdPostId); } });
document.addEventListener("click",function(ev){ var row=ev.target&&ev.target.closest?ev.target.closest(".pd-comment-tap"):null; if(row&&row.dataset&&row.dataset.uid){ var a=ev.target.closest("a"); if(a)return; ev.preventDefault(); try{viewProfile(parseInt(row.dataset.uid,10));}catch(e){} } });
function pdCommentHtml(c){var uid=c.user_id?String(c.user_id):"";return `\n        <div class="pd-comment${uid?" pd-comment-tap":""}"${uid?` data-uid="${uid}" onclick="event.stopPropagation();viewProfile(${parseInt(uid,10)})" style="cursor:pointer;"`:""}>\n          <div class="pd-comment-av">${avatarHtml(c.name,c.icon_url)}</div>\n          <div class="pd-comment-body">\n            <div class="pd-comment-name">${escapeHtml(c.name)}</div>\n            <div class="pd-comment-text">${escapeHtml(c.text)}</div>\n            <div class="pd-comment-time" data-ts="${escapeHtml(c.created_at||"")}" title="${escapeHtml(c.created_at||"")}">${relTime(c.created_at)}${(c.id&&uid&&typeof myUserId!=="undefined"&&myUserId&&String(myUserId)===uid)?` <button type="button" class="pd-comment-del" data-cid="${escAttr(String(c.id))}" onclick="event.stopPropagation();koeDeleteMyComment(this,'${escAttr(String(c.id))}')">削除</button>`:""}</div>\n          </div>\n        </div>`;}
async function koeDeleteMyComment(btn,cid){try{if(!await showConfirmModal("この返信を削除しますか?"))return;var pid=window.__pdPostId||(typeof __pdPostId!=="undefined"?__pdPostId:null)||window.__koeCurrentFeedPostId;if(!pid){toast("投稿IDが取得できません","error");return}btn.disabled=true;var r=await callApi("delete_feed_post_comment",String(pid),String(cid));if(r&&r.ok){var row=btn.closest(".pd-comment");if(row)row.remove();toast("返信を削除しました");sfx("close")}else{btn.disabled=false;toast("削除できませんでした"+(r&&r.status?" (HTTP "+r.status+")":""),"error")}}catch(e){toast("削除エラー: "+e,"error")}}
function __pdWireCommentScroll(postId){
  // 返信は #pdComments(独自スクロール枠 max-height:40vh)の中でスクロールする
  var sc=document.getElementById("pdComments");
  if(sc&&!sc.__pdScrollWired){
    sc.__pdScrollWired=true;
    sc.addEventListener("scroll",function(){
      if(sc.scrollTop+sc.clientHeight>=sc.scrollHeight-160){ window.__pdLoadMoreComments(window.__pdPostId||__pdPostId); }
    },{passive:true});
  }
  // モーダル本体のスクロールでも一応拾う
  var mb=document.querySelector("#postDetailModal .modal-body");
  if(mb&&!mb.__pdScrollWired){
    mb.__pdScrollWired=true;
    mb.addEventListener("scroll",function(){
      if(mb.scrollTop+mb.clientHeight>=mb.scrollHeight-160){ window.__pdLoadMoreComments(window.__pdPostId||__pdPostId); }
    },{passive:true});
  }
  __pdObserveSentinel();
}
function __pdObserveSentinel(){
  try{
    var sent=document.getElementById("pdCommentsSentinel");
    if(!sent) return;
    var root=document.getElementById("pdComments")||null;
    if(window.__pdIO){ try{window.__pdIO.disconnect();}catch(e){} }
    window.__pdIO=new IntersectionObserver(function(entries){
      for(var i=0;i<entries.length;i++){ if(entries[i].isIntersecting){ window.__pdLoadMoreComments(window.__pdPostId||__pdPostId); } }
    },{root:root,rootMargin:"150px"});
    window.__pdIO.observe(sent);
  }catch(e){}
}async function sendPostDetailReply(){const input=document.getElementById("pdReplyInput"),text=input.value.trim();if(!text||!__pdPostId)return;input.value="";const rbtn=document.getElementById("pdReplyBtn");rbtn&&(rbtn.disabled=!0,rbtn.classList.add("loading"));const result=await callApi("reply_timeline_post",__pdPostId,text);rbtn&&(rbtn.disabled=!1,rbtn.classList.remove("loading")),result.ok?(toast("返信しました"),openPostDetail(null,__pdPostId)):toast(`返信失敗: ${koeErrMsg(result)}`.slice(0,120),"error")}async function promptTimelineReply(evt,postId){evt.stopPropagation();const text=await showInputModal("返信を入力","コメントを書く...");if(!text)return;const result=await callApi("reply_timeline_post",postId,text);result.ok?(toast("返信しました"),await loadTimeline()):toast(`返信失敗: ${koeErrMsg(result)}`.slice(0,120),"error")}async function promptTimelineReport(evt,targetUserId){evt.stopPropagation();const reason=await showInputModal("通報理由を入力","投稿者を通報します");if(!reason)return;const result=await callApi("report_timeline_post",targetUserId,reason);toast(result.ok?"通報しました":`通報失敗: ${koeErrMsg(result)}`.slice(0,120),result.ok?void 0:"error")}async function deleteOwnPost(evt,postId,isTalk){if(evt.stopPropagation(),!await showConfirmModal("この投稿を削除しますか?"))return;const result=await callApi("delete_own_timeline_post",postId,isTalk?1:0);result.ok?(toast("削除しました"),loadTimeline(!1)):toast(`削除失敗: ${koeErrMsg(result)}`.slice(0,120),"error")}async function loadReceivers(kind){if(!document.getElementById("cheeringList"))return;document.querySelectorAll(".chip").forEach(c=>c.classList.toggle("active",c.dataset.kind===kind));const list=document.getElementById("cheeringList");list.innerHTML=skeletonCards(4);const result=await callApi("get_receivers",kind);result.ok?result.receivers.length?(window._cheeringReceivers=result.receivers,list.innerHTML=result.receivers.map((r,i)=>`\n    <div class="card" onclick="requestCheeringCall(${i})">\n      ${avatarHtml(r.name,r.icon_url)}\n      <div class="card-body">\n        <div class="card-name">${escapeHtml(r.name||"user "+r.user_id)} <span class="uid-tag">ID:${r.user_id}</span></div>\n        <div class="card-sub">${escapeHtml(r.status_text||r.message||"タップしてお願い通話を発信")}</div>\n      </div>\n      <span class="profile-link" onclick='event.stopPropagation(); viewProfile(${r.user_id})' title="プロフィールを見る"><svg class="ico" viewBox="0 0 24 24" fill="currentColor"><circle cx="12" cy="8" r="4"/><path d="M4 20a8 8 0 0 1 16 0 1 1 0 0 1-1 1H5a1 1 0 0 1-1-1Z"/></svg></span>\n      <span style="font-size:20px;"><svg class="ico" viewBox="0 0 24 24" fill="currentColor"><path d="M6.6 10.8a15 15 0 0 0 6.6 6.6l2.2-2.2a1 1 0 0 1 1-.25 11 11 0 0 0 3.5.56 1 1 0 0 1 1 1V20a1 1 0 0 1-1 1A17 17 0 0 1 3 4a1 1 0 0 1 1-1h3.3a1 1 0 0 1 1 1 11 11 0 0 0 .56 3.5 1 1 0 0 1-.25 1L6.6 10.8Z"/></svg></span>\n    </div>\n  `).join("")):list.innerHTML='<div class="empty-msg">受け手が見つかりません</div>':list.innerHTML=`<div class="empty-msg">読み込めませんでした<br><button class="btn-secondary" style="width:auto;margin-top:10px;" onclick="reloadCurrentView()">再試行</button></div>`}async function joinCallFor(userId){setCheeringStatus("トークルームを確認しています...");const result=await callApi("join_call",userId);result.ok?(setCheeringStatus("通話ウィンドウを開きました"),onJoinSuccess(result)):setCheeringStatus(`参加失敗: ${koeErrMsg(result)}`)}function setCheeringStatus(text){let statusEl=document.getElementById("cheeringStatusLine");if(!statusEl){const cl=document.getElementById("cheeringList");if(!cl)return;statusEl=document.createElement("div"),statusEl.id="cheeringStatusLine",statusEl.className="empty-msg",statusEl.style.padding="8px 0",cl.insertAdjacentElement("beforebegin",statusEl)}statusEl.textContent=text}let cheeringCallActive=!1,cheeringPollTimer=null,cheeringTarget=null;async function requestCheeringCall(index){if(cheeringCallActive)return;const rcv=(window._cheeringReceivers||[])[index];if(!rcv)return;cheeringCallActive=!0,cheeringTarget=null;const label=rcv.name||"user "+rcv.user_id;showCheeringCallStatus(`<svg class="ico" viewBox="0 0 24 24" fill="currentColor"><path d="M6.6 10.8a15 15 0 0 0 6.6 6.6l2.2-2.2a1 1 0 0 1 1-.25 11 11 0 0 0 3.5.56 1 1 0 0 1 1 1V20a1 1 0 0 1-1 1A17 17 0 0 1 3 4a1 1 0 0 1 1-1h3.3a1 1 0 0 1 1 1 11 11 0 0 0 .56 3.5 1 1 0 0 1-.25 1L6.6 10.8Z"/></svg> ${label} に発信中...`,!0);const start=await callApi("start_cheering_call",rcv.receiver_id,rcv.user_id,rcv.name);if(appendCheeringDebug("start_cheering_call",start),!start||!start.ok)return showCheeringCallStatus(`発信失敗: ${JSON.stringify(start&&(start.error||start.steps)||"")}`.slice(0,200),!1),void(cheeringCallActive=!1);let channel=start.channel||null;if(cheeringTarget=start.target_id||null,channel&&cheeringTarget)return void await confirmCheeringCall(channel,cheeringTarget,rcv.name);let attempts=0;cheeringPollTimer=setInterval(async()=>{if(!cheeringCallActive)return void clearInterval(cheeringPollTimer);attempts++;const chk=await callApi("check_cheering_call");appendCheeringDebug(`check_cheering_call #${attempts}`,chk),chk&&chk.channel&&(channel=chk.channel),chk&&chk.target_id&&(cheeringTarget=chk.target_id),showCheeringCallStatus(`<svg class="ico" viewBox="0 0 24 24" fill="currentColor"><path d="M6.6 10.8a15 15 0 0 0 6.6 6.6l2.2-2.2a1 1 0 0 1 1-.25 11 11 0 0 0 3.5.56 1 1 0 0 1 1 1V20a1 1 0 0 1-1 1A17 17 0 0 1 3 4a1 1 0 0 1 1-1h3.3a1 1 0 0 1 1 1 11 11 0 0 0 .56 3.5 1 1 0 0 1-.25 1L6.6 10.8Z"/></svg> ${label} の応答を待っています... (${attempts}/30)`,!0),channel&&cheeringTarget?(clearInterval(cheeringPollTimer),await confirmCheeringCall(channel,cheeringTarget,rcv.name)):attempts>=30&&(clearInterval(cheeringPollTimer),await cancelCheeringCall(),showCheeringCallStatus("応答がありませんでした(タイムアウト)",!1))},2e3)}async function confirmCheeringCall(channel,target,name){showCheeringCallStatus("相手が応答。通話を確立中...",!1);const res=await callApi("confirm_and_open_cheering_call",target,channel,name);appendCheeringDebug("confirm_and_open_cheering_call",res),res&&res.ok?(window.__koeCheerConnected?window.__koeCheerConnected(target,channel,name):showCheeringCallStatus("通話中",!1)):showCheeringCallStatus(`通話確立失敗: ${JSON.stringify(res&&(res.error||res.steps)||"")}`.slice(0,200),!1),cheeringCallActive=!1}async function cancelCheeringCall(){cheeringPollTimer&&clearInterval(cheeringPollTimer);const wasActive=cheeringCallActive;if(cheeringCallActive=!1,cheeringTarget){appendCheeringDebug("cancel_cheering_call",await callApi("cancel_cheering_call",cheeringTarget))}wasActive&&showCheeringCallStatus("発信をキャンセルしました",!1)}function showCheeringCallStatus(text,showCancel){let el=document.getElementById("cheeringStatusLine");if(!el){const cl=document.getElementById("cheeringList");if(!cl)return;el=document.createElement("div"),el.id="cheeringStatusLine",el.className="empty-msg",el.style.padding="8px 0",cl.insertAdjacentElement("beforebegin",el)}el.innerHTML=escapeHtml(text)+(showCancel?' <button class="btn-secondary" style="width:auto;margin-left:8px;padding:4px 12px;" onclick="cancelCheeringCall()">キャンセル</button>':"")}function appendCheeringDebug(labelText,obj){console.log("[cheering]",labelText,obj);let box=document.getElementById("cheeringDebugBox");if(!box){const dv=document.createElement("div");dv.className="section-divider",dv.textContent="お願い通話デバッグログ(初回テスト用・レスポンス構造確認)",document.getElementById("page-cheering").appendChild(dv),box=document.createElement("pre"),box.id="cheeringDebugBox",box.className="inspect-result",box.style.marginTop="8px",document.getElementById("page-cheering").appendChild(box)}box.textContent+=`\n[${(new Date).toLocaleTimeString()}] ${labelText}\n${JSON.stringify(obj,null,2)}\n`,box.scrollTop=box.scrollHeight}async function loadAnnouncements(){if(!document.getElementById("announcementsList"))return;const list=document.getElementById("announcementsList");if(!list)return;list.innerHTML='<div class="empty-msg" style="padding:8px 0;">読み込み中...</div>';const result=await callApi("get_announcements");result.ok&&result.announcements&&result.announcements.length?list.innerHTML=result.announcements.map(a=>`\n    <div class="card" ${a.user_id?`onclick='viewProfile(${a.user_id})'`:""} style="cursor:${a.user_id?"pointer":"default"};">\n      ${avatarHtml(a.name,a.icon_url)}\n      <div class="card-body">\n        <div class="card-name">${escapeHtml(a.name||"")} ${a.user_id?`<span class="uid-tag">ID:${a.user_id}</span>`:""}</div>\n        <div class="tl-text" style="margin:4px 0 0;">${escapeHtml(a.description||"")}</div>\n        ${a.open_at?`<div class="card-meta"><svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2" stroke-linecap="round"/></svg> ${escapeHtml(a.open_at)}</div>`:""}\n      </div>\n    </div>\n  `).join(""):list.innerHTML='<div class="empty-msg" style="padding:8px 0;">お知らせはありません</div>'}let roomFeed="all",followedUserIds=null;async function loadPostsInto(boxId,uid){const box=document.getElementById(boxId);if(!box)return;box.innerHTML=skeletonCards(2);let r;try{r=await callApi("get_user_posts",String(uid),"")}catch(e){r=null}if(!r||!r.ok){box.innerHTML='<div class="empty-msg" style="padding:6px 0;">投稿を取得できませんでした</div>';return}if(!r.posts||!r.posts.length){box.innerHTML='<div class="empty-msg" style="padding:6px 0;">最近の投稿が見つかりませんでした(koetomo側の仕様で最近のもの以外は取得できません)</div>';return}try{koeAutoDeleteRegulated(r.posts)}catch(e){}box.innerHTML=r.posts.map(postCardHtml).join("")}async function doUserSearch(){const inp=document.getElementById("userSearchInput");if(!inp)return;const name=inp.value.trim();const box=document.getElementById("userSearchResults");if(!box)return;if(!name){box.innerHTML='<div class="empty-msg">名前を入力してください</div>';return}box.innerHTML=skeletonCards(3);const r=await callApi("search_users",name,"1");if(!r||!r.ok){box.innerHTML='<div class="empty-msg">検索に失敗しました</div>';return}if(!r.users||!r.users.length){box.innerHTML='<div class="empty-msg">見つかりませんでした</div>';return}box.innerHTML=r.users.map(function(u){return'<div class="card" onclick="viewProfile('+u.user_id+')">'+avatarHtml(u.name,u.icon_url)+'<div class="card-body"><div class="card-name">'+escapeHtml(u.name||"user "+u.user_id)+' <span class="uid-tag">ID:'+u.user_id+"</span></div>"+(u.age?'<div class="card-sub">'+escapeHtml(String(u.age))+"歳</div>":"")+"</div></div>"}).join("")}function renderHistoryList(box,list){if(!box)return;if(!list||!list.length){box.innerHTML='<div class="empty-msg" style="padding:6px 0;">履歴はありません</div>';return}box.innerHTML=list.map(function(h){var amt=Number(h.amount)||0;var sign=amt>0?"+":"";var col=amt>0?"var(--accent,#2AC1C7)":"var(--danger,#e66)";return'<div class="card" style="display:flex;justify-content:space-between;align-items:center;"><div class="card-body"><div class="card-name" style="font-size:13px;">'+escapeHtml(h.title||"-")+'</div><div class="card-sub">'+escapeHtml(relTime(h.created_at)||h.created_at||"")+(h.expired_at?" ・期限 "+escapeHtml(h.expired_at):"")+'</div></div><div style="font-weight:800;color:'+col+';">'+sign+amt+"</div></div>"}).join("")}async function loadWalletHistory(){const cb=document.getElementById("coinHistoryList"),pb=document.getElementById("pointHistoryList");if(cb)cb.innerHTML=skeletonCards(2);if(pb)pb.innerHTML=skeletonCards(2);const res=await Promise.all([callApi("get_coin_history"),callApi("get_point_history")]);renderHistoryList(cb,res[0]&&res[0].ok?res[0].histories:[]);renderHistoryList(pb,res[1]&&res[1].ok?res[1].histories:[])}async function openPointExchange(){const r=await callApi("point_exchange_url");if(!r||!r.ok||!r.url){toast("交換ページを開けませんでした","error");return}if(window.AndroidApi&&window.AndroidApi.openUrl)window.AndroidApi.openUrl(r.url);else window.open(r.url,"_blank")}function renderCallHistoryList(box,items,label){if(!box)return;if(!items||!items.length){box.innerHTML='<div class="empty-msg" style="padding:6px 0;">'+label+"はありません</div>";return}box.innerHTML=items.map(function(u){return'<div class="card" onclick="viewProfile('+u.user_id+')">'+avatarHtml(u.name,u.icon_url)+'<div class="card-body"><div class="card-name">'+escapeHtml(u.name||"user "+u.user_id)+' <span class="uid-tag">ID:'+u.user_id+'</span></div><div class="card-sub">'+escapeHtml(relTime(u.created_at)||u.created_at||"")+"</div></div></div>"}).join("")}async function loadCallHistory(){const mb=document.getElementById("missedCallsList"),tb=document.getElementById("talkRequestsList");if(mb)mb.innerHTML=skeletonCards(2);if(tb)tb.innerHTML=skeletonCards(2);const res=await Promise.all([callApi("get_missed_calls"),callApi("get_talk_requests")]);renderCallHistoryList(mb,res[0]&&res[0].ok?res[0].items:[],"不在着信");renderCallHistoryList(tb,res[1]&&res[1].ok?res[1].items:[],"トークリクエスト")}async function ensureFollowedSet(){if(followedUserIds)return followedUserIds;const res=await callApi("get_followees",null,1);return res.ok?(followedUserIds=new Set((res.users||[]).map(u=>u.user_id)),followedUserIds):null}async function loadGroupRooms(){const list=document.getElementById("callList");list.innerHTML=skeletonCards(3);const result=await callApi("list_group_rooms",1);if(!result.ok)return void(list.innerHTML=`<div class="empty-msg">一覧を取得できませんでした<br><button class="btn-secondary" style="width:auto;margin-top:10px;" onclick="reloadCurrentView()">再試行</button></div>`);let rooms=result.rooms||[];if("following"===roomFeed){const followed=await ensureFollowedSet();followed&&(rooms=rooms.filter(r=>followed.has(r.owner_user_id)))}window.__roomsCache=rooms,applyRoomView()}function applyRoomView(){let rooms=(window.__roomsCache||[]).slice();const q=(document.getElementById("roomSearchInput")&&document.getElementById("roomSearchInput").value||"").trim().toLowerCase();q&&(rooms=rooms.filter(r=>(r.title||"").toLowerCase().includes(q)||(r.owner_name||"").toLowerCase().includes(q)||String(r.owner_user_id).includes(q)));const sort=document.getElementById("roomSortSelect")&&document.getElementById("roomSortSelect").value||"pop",total=r=>(r.speaker_count||0)+(r.listener_count||0);"pop"===sort?rooms.sort((a,b)=>total(b)-total(a)):"few"===sort?rooms.sort((a,b)=>total(a)-total(b)):"new"===sort&&rooms.sort((a,b)=>String(b.created_at||"").localeCompare(String(a.created_at||""))),renderRooms(rooms)}function renderRooms(rooms){const list=document.getElementById("callList");rooms=rooms||[];rooms.length?list.innerHTML=rooms.map(r=>{const total=(r.speaker_count||0)+(r.listener_count||0),hot=total>=5;return`\n    <div class="card room-card ${hot?"room-hot":""}" onclick="joinViaCard(this, () => joinGroupRoom(${r.owner_user_id}))">\n      <div class="room-count-badge ${hot?"hot":""}">${hot?'<span class="room-fire">▲</span>':""}${total}<small>人</small></div>\n      ${avatarHtml(r.owner_name||r.title,r.owner_icon)}\n      <div class="card-body">\n        <div class="card-name">${escapeHtml(r.title)}</div>\n        <div class="card-sub"><svg class="ico" viewBox="0 0 24 24" fill="currentColor"><path d="M3 8l4.5 3.5L12 5l4.5 6.5L21 8l-1.5 10.5a1 1 0 0 1-1 .5H5.5a1 1 0 0 1-1-.5L3 8Z"/></svg> ${escapeHtml(r.owner_name||"user "+r.owner_user_id)} <span class="uid-tag">ID:${r.owner_user_id}</span></div>\n        <div class="card-meta"><svg class="ico" viewBox="0 0 24 24" fill="currentColor"><rect x="9" y="2.5" width="6" height="11" rx="3"/><path d="M6 11a6 6 0 0 0 12 0" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/><rect x="11" y="18" width="2" height="3.4" rx="1"/></svg> 発言 ${r.speaker_count??0} ・ <svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 13v-1a8 8 0 0 1 16 0v1"/><rect x="2.5" y="13" width="4" height="7" rx="2" fill="currentColor" stroke="none"/><rect x="17.5" y="13" width="4" height="7" rx="2" fill="currentColor" stroke="none"/></svg> リスナー ${r.listener_count??0}</div>\n      </div>\n      <span class="profile-link" onclick='event.stopPropagation(); viewProfile(${r.owner_user_id})' title="プロフィールを見る"><svg class="ico" viewBox="0 0 24 24" fill="currentColor"><circle cx="12" cy="8" r="4"/><path d="M4 20a8 8 0 0 1 16 0 1 1 0 0 1-1 1H5a1 1 0 0 1-1-1Z"/></svg></span>\n      <span style="font-size:20px;"><svg class="ico" viewBox="0 0 24 24" fill="currentColor"><path d="M6.6 10.8a15 15 0 0 0 6.6 6.6l2.2-2.2a1 1 0 0 1 1-.25 11 11 0 0 0 3.5.56 1 1 0 0 1 1 1V20a1 1 0 0 1-1 1A17 17 0 0 1 3 4a1 1 0 0 1 1-1h3.3a1 1 0 0 1 1 1 11 11 0 0 0 .56 3.5 1 1 0 0 1-.25 1L6.6 10.8Z"/></svg></span>\n    </div>\n  `}).join(""):list.innerHTML=`<div class="empty-msg">${"following"===roomFeed?"フォロー中の人が開いている枠はありません":"現在開いているルームがありません"}</div>`}function switchRoomFeed(feed){feed!==roomFeed&&(roomFeed=feed,document.querySelectorAll(".room-feed-chip").forEach(c=>c.classList.toggle("active",c.dataset.feed===feed)),loadGroupRooms())}let currentRoomId=null,currentRoomOwnerId=null,roomListRefreshTimer=null,applicantPollTimer=null,isJoiningCall=!1,whackTimer=null,whackScore=0,miniGameWatch=null;function startWhack(){whackScore=0,document.getElementById("whackScore").textContent="0";const grid=document.getElementById("whackGrid");grid.innerHTML="";for(let i=0;i<9;i++){const hole=document.createElement("div");hole.className="whack-hole",hole.addEventListener("click",()=>{hole.classList.contains("up")&&(hole.classList.remove("up"),whackScore++,document.getElementById("whackScore").textContent=whackScore)}),grid.appendChild(hole)}const holes=grid.querySelectorAll(".whack-hole");whackTimer=setInterval(()=>{holes.forEach(h=>h.classList.remove("up")),holes[Math.floor(Math.random()*holes.length)].classList.add("up")},780)}function stopWhack(){clearInterval(whackTimer),whackTimer=null}function showMiniGame(){document.getElementById("miniGameOverlay").style.display="flex",startWhack(),clearInterval(miniGameWatch);let waited=0;miniGameWatch=setInterval(()=>{waited+=500;const leaveBtn=document.getElementById("callLeaveBtn");(leaveBtn&&!leaveBtn.disabled||waited>9e4)&&hideMiniGame()},500)}function hideMiniGame(){clearInterval(miniGameWatch),miniGameWatch=null,stopWhack();const ov=document.getElementById("miniGameOverlay");ov&&(ov.style.display="none")}let roomCommentTimer=null;/* 通話画面上に流れるコメントバブル */
function koeChatBubble(name,text,icon){try{var ov=document.getElementById("callOverlay");if(!ov||ov.style.display==="none")return;var box=document.getElementById("koeChatBubbles");if(!box){box=document.createElement("div");box.id="koeChatBubbles";ov.appendChild(box)}var b=document.createElement("div");b.className="koe-chat-bubble";b.innerHTML=(icon?'<img src="'+escAttr(icon)+'" onerror="this.remove()">':'')+'<div><b>'+escapeHtml(name)+'</b><span>'+escapeHtml(text)+'</span></div>';b.addEventListener("click",function(){try{var tg=document.getElementById("callChatToggle");tg&&tg.click()}catch(e){}b.remove()});box.appendChild(b);while(box.children.length>2)box.removeChild(box.firstChild);setTimeout(function(){b.classList.add("out");setTimeout(function(){b.remove()},400)},3500)}catch(e){}}
/* ==== 公式と同じ Firebase Realtime Database の購読(REST streaming / EventSource) ====
   公式アプリは枠のコメント・メンバー役割・状態を RTDB(/api/rooms/{id}/...)でリアルタイム受信している。
   Firebase SDK は使えないので、同じデータを REST の text/event-stream で受ける。失敗時は従来のポーリングに戻る。 */
var KOE_RTDB="https://koetomo-bb8bb.firebaseio.com/api/rooms/";
window.__rtdb={es:{},members:null,comments:{},roomId:null,retry:null};
function koeRtdbStop(){try{Object.keys(window.__rtdb.es).forEach(function(k){try{window.__rtdb.es[k].close()}catch(e){}});window.__rtdb.es={};window.__rtdb.streams={};window.__rtdb.retries={};window.__rtdb.members=null;window.__rtdb.comments={};window.__rtdb.__cInit=false;window.__rtdb.roomId=null;window.__rtdbAlive=0;if(window.__rtdb.retry){clearTimeout(window.__rtdb.retry);window.__rtdb.retry=null}}catch(e){}}
function koeRtdbStart(roomId){koeRtdbStop();if(!roomId||typeof EventSource==="undefined")return;window.__rtdb.roomId=String(roomId);window.__rtdb.streams={};window.__rtdb.retries={};
var base=KOE_RTDB+encodeURIComponent(String(roomId));
/* 各ストリームは独立に開閉・再接続する(1本の権限エラーで全部を落として15秒ごとに全再接続していた問題の修正) */
window.__rtdb.streams.members={url:base+"/members.json",on:function(type,path,data){koeRtdbApplyMembers(type,path,data)}};
window.__rtdb.streams.comments={url:base+"/comments.json?orderBy=%22%24key%22&limitToLast=15",on:function(type,path,data){koeRtdbApplyComments(type,path,data)}};
window.__rtdb.streams.comment_enabled={url:base+"/comment_enabled.json",on:function(type,path,data){try{if(data===null||data===undefined)return;var v=(data===true||data===1||data==="true"||data==="1")?true:((data===false||data===0||data==="false"||data==="0")?false:null);if(v!==null)koeApplyCommentEnabled(v)}catch(e){}}};
window.__rtdb.streams.status={url:base+"/status.json",on:function(type,path,data){try{if(data===null)return;var st=String(typeof data==="object"?(data.status||data.state||JSON.stringify(data)):data).toLowerCase();callLog("RTDB status: "+st);if(/close|end|finish/.test(st)){onRoomClosed()}}catch(e){}}};
Object.keys(window.__rtdb.streams).forEach(function(n){koeRtdbOpen(n)});
try{callLog("RTDB購読開始: room "+roomId)}catch(e){}}
function koeRtdbOpen(name){try{var st=window.__rtdb.streams&&window.__rtdb.streams[name];if(!st||!window.__rtdb.roomId)return;var es=new EventSource(st.url);window.__rtdb.es[name]=es;
var handler=function(ev){try{window.__rtdbAlive=Date.now();window.__rtdb.retries[name]=0;var d=JSON.parse(ev.data||"{}");st.on(ev.type,d.path||"/",d.data)}catch(e){}};
es.addEventListener("put",handler);es.addEventListener("patch",handler);es.addEventListener("keep-alive",function(){window.__rtdbAlive=Date.now()});
es.addEventListener("cancel",function(){try{callLog("RTDB "+name+": 権限なし(cancel)")}catch(e){}koeRtdbFail(name)});es.addEventListener("auth_revoked",function(){koeRtdbFail(name)});
es.onerror=function(){/* 一時的な切断はブラウザが自動再接続する。閉じられた場合のみ再試行 */if(es.readyState===2){koeRtdbFail(name)}};}catch(e){koeRtdbFail(name)}}
function koeRtdbFail(name){try{var rid=window.__rtdb.roomId;if(!rid)return;
if(!name){Object.keys(window.__rtdb.es).forEach(function(k){koeRtdbFail(k)});return}
try{var es=window.__rtdb.es[name];if(es)es.close()}catch(e){}delete window.__rtdb.es[name];
if(name==="comments"){window.__rtdb.__cInit=false}
if(name==="members"){window.__rtdb.members=null}
var n=(window.__rtdb.retries[name]=(window.__rtdb.retries[name]||0)+1);var wait=Math.min(15000*Math.pow(2,n-1),300000); /* 15s→30s→…最大5分 */
try{callLog("RTDB "+name+": 切断 → "+Math.round(wait/1000)+"秒後に再接続("+n+"回目)")}catch(e){}
setTimeout(function(){if(currentRoomId&&String(currentRoomId)===String(rid)&&window.__rtdb.roomId===String(rid)&&!window.__rtdb.es[name])koeRtdbOpen(name)},wait)}catch(e){}}
function koeRtdbSetPath(obj,path,data){var parts=String(path||"/").split("/").filter(Boolean);if(!parts.length){return data&&typeof data==="object"?data:{}}var cur=obj||{};var o=cur;for(var i=0;i<parts.length-1;i++){if(!o[parts[i]]||typeof o[parts[i]]!=="object")o[parts[i]]={};o=o[parts[i]]}if(data===null)delete o[parts[parts.length-1]];else o[parts[parts.length-1]]=data;return cur}
async function koeRtdbApplyMembers(type,path,data){try{var m=window.__rtdb.members||{};if(type==="put"){m=koeRtdbSetPath(path==="/"?{}:m,path,data)}else{/* patch: data はオブジェクト(相対パスのキー) */var base=path;Object.keys(data||{}).forEach(function(k){m=koeRtdbSetPath(m,(base==="/"?"":base)+"/"+k,data[k])})}window.__rtdb.members=m;var uids=Object.keys(m).map(Number).filter(Boolean);var known={};try{var r=window.__roomRoster;(r?[].concat(r.speakers||[],r.listeners||[],r.applicants||[]):[]).forEach(function(u){var id=Number(u.user_id||u.userId);if(id)known[id]=u})}catch(e){}try{var seen=window.__callSession&&window.__callSession.seen||{};Object.keys(seen).forEach(function(k){if(!known[k])known[k]={user_id:Number(k),name:seen[k].name,icon_url:seen[k].icon}})}catch(e){}var unknown=uids.filter(function(u){return !known[u]});if(unknown.length){try{var rr=await callApi("resolve_users",unknown.join(","));(rr&&rr.users||[]).forEach(function(u){known[u.user_id]={user_id:u.user_id,name:u.name,icon_url:u.icon_url}})}catch(e){}}var sp=[],ls=[],ap=[];uids.forEach(function(uid){var role=String((m[uid]&&(m[uid].role||m[uid]))||"").toLowerCase();var u=known[uid]||{user_id:uid,name:"user "+uid,icon_url:""};u={user_id:uid,name:u.name||("user "+uid),icon_url:u.icon_url||""};if(role==="speaker")sp.push(u);else if(role==="speaker_applicant")ap.push(u);else ls.push(u)});var owner=window.__callOwnerUid||(window.__roomRoster&&window.__roomRoster.owner)||0;var res={ok:true,room_id:currentRoomId,owner_user_id:owner,speakers:sp,listeners:ls,speaker_applicants:ap,speaker_count:sp.length,listener_count:ls.length};updateCallRoster(res);renderApplicants(null===currentRoomOwnerId&&ap||[]);try{koeSyncPublish(false)}catch(e){}try{window.__koeSyncSubs&&window.__koeSyncSubs()}catch(e){}}catch(e){try{callLog("RTDB members エラー: "+e)}catch(_){}}}
function koeRtdbApplyComments(type,path,data){try{window.__rtdb.__cInit=true;var c=window.__rtdb.comments||{};if(type==="put"){if(path==="/"){c=data&&typeof data==="object"?data:{}}else{c=koeRtdbSetPath(c,path,data)}}else{Object.keys(data||{}).forEach(function(k){c=koeRtdbSetPath(c,(path==="/"?"":path)+"/"+k,data[k])})}window.__rtdb.comments=c;var keys=Object.keys(c).sort();try{if(!window.__rtdb.__cLogged||!(type==="put"&&path==="/")){window.__rtdb.__cLogged=true;callLog("RTDB comments "+type+" "+path+" → "+keys.length+"件")}}catch(e){}var list=koeRtdbCommentList();reloadRoomComments(list)}catch(e){try{callLog("RTDB comments エラー: "+e)}catch(_){}}}
function koeRtdbCommentsLive(){try{var es=window.__rtdb&&window.__rtdb.es&&window.__rtdb.es.comments;return !!(es&&es.readyState===1&&window.__rtdb.__cInit&&window.__rtdbAlive&&Date.now()-window.__rtdbAlive<120000)}catch(e){return false}}
function koeRtdbCommentList(){var c=(window.__rtdb&&window.__rtdb.comments)||{};return Object.keys(c).sort().map(function(k){var x=c[k]||{};var ui=x.user_info||x.user||{};return{id:k,user_id:Number(x.user_id||x.userId||0),name:ui.name||x.name||x.user_name||"",icon_url:(typeof iconUrlJs==="function"?iconUrlJs(ui.profile_picture_file_path):(ui.profile_picture_file_path||x.icon_url||"")),text:x.comment||x.text||x.description||x.body||"",created_at:x.created_at||"",explicit:(Number(x.is_explicit)===1||x.is_explicit===true)?1:0,penalty:!!(ui.is_in_penalty_period===true||Number(ui.is_in_penalty_period)===1||x.is_in_penalty_period===true)}}).filter(function(x){return x.text})}
function iconUrlJs(p){if(!p)return"";if(/^https?:\/\//.test(p))return p;try{if(window.__koeIconBase)return window.__koeIconBase.replace(/\/+$/,"")+"/"+String(p).replace(/^\/+/,"")}catch(e){}return""}
function startRoomCommentPolling(){try{if(!window.__koeIconBase)callApi("get_icon_base").then(function(r){if(r&&r.ok&&r.base)window.__koeIconBase=r.base}).catch(function(){})}catch(e){}stopRoomCommentPolling();window.__chatNotified={};window.__chatPending=[];window.__chatMine=[];window.__chatStore=[];window.__chatSeq=0;window.__chatSys=[];window.__chatNotifiedInit=false;window.__chatUnread=0;try{var __lg=document.getElementById("callChatLog");if(__lg){__lg.innerHTML="";__lg.__sig=""}}catch(e){}try{var tg=document.getElementById("callChatToggle"),bd=tg&&tg.querySelector(".callv2-badge");if(bd)bd.style.display="none"}catch(e){}try{koeRtdbStart(currentRoomId)}catch(e){}roomCommentTimer=setInterval(function(){reloadRoomComments()},1500),reloadRoomComments()}function stopRoomCommentPolling(){try{koeRtdbStop()}catch(e){}roomCommentTimer&&(clearInterval(roomCommentTimer),roomCommentTimer=null)}
/* ===== 通話中チャット: ソース(RTDB / REST)に依存しない統合ストア =====
   RTDB(最新15件) と REST(全件) は id 体系が違うため、本文(+名前)で系列を突き合わせて1本の履歴に統合する。
   これにより「ソースが切り替わるたびに通知が鳴る／一覧が縮む／重複する」を防ぐ。 */
function koeChatEq(a,b){if(a.text!==b.text)return false;if(a.name&&b.name&&a.name!==b.name)return false;if(Number(a.user_id)>0&&Number(b.user_id)>0&&Number(a.user_id)!==Number(b.user_id))return false;return true}
function koeChatMerge(list){
  var S=window.__chatStore||(window.__chatStore=[]);var L=(list||[]).filter(function(x){return x&&x.text});
  if(!L.length)return S;
  var seq=window.__chatSeq||0,now=Date.now();
  function mk(x){seq++;return{k:"c"+seq,user_id:Number(x.user_id)||0,name:x.name||"",icon_url:x.icon_url||"",text:x.text,created_at:x.created_at||"",explicit:(Number(x.explicit)===1||Number(x.is_explicit)===1)?1:0,penalty:!!(x.penalty||x.is_in_penalty_period===true),addedAt:now}}
  function enrich(dst,src){if(!dst.explicit&&(Number(src.explicit)===1||Number(src.is_explicit)===1))dst.explicit=1;if(!dst.penalty&&(src.penalty||src.is_in_penalty_period===true))dst.penalty=true;if(!dst.name&&src.name)dst.name=src.name;if(!dst.icon_url&&src.icon_url)dst.icon_url=src.icon_url;if(!(Number(dst.user_id)>0)&&Number(src.user_id)>0)dst.user_id=Number(src.user_id);if(!dst.created_at&&src.created_at)dst.created_at=src.created_at}
  if(!S.length){L.forEach(function(x){S.push(mk(x))});window.__chatSeq=seq;return S}
  /* 1) L の先頭が S の途中(o)に重なる場合: S[0..o) + L(重なり分は既存を流用) */
  var o,j,m,ok;
  for(o=0;o<S.length;o++){m=Math.min(S.length-o,L.length);ok=true;for(j=0;j<m;j++){if(!koeChatEq(S[o+j],L[j])){ok=false;break}}if(ok)break}
  if(o<S.length){m=Math.min(S.length-o,L.length);for(j=0;j<m;j++)enrich(S[o+j],L[j]);
    if(L.length>m){for(j=m;j<L.length;j++)S.push(mk(L[j]))}
    /* L が S の末尾より短い(古い窓)場合は S の残りをそのまま保持 */
  }else{
    /* 2) S の先頭が L の途中(p)に重なる場合(L の方が古い履歴を持つ): L[0..p) + S + L の余り */
    var p,found=-1;
    for(p=1;p<L.length;p++){m=Math.min(L.length-p,S.length);ok=true;for(j=0;j<m;j++){if(!koeChatEq(L[p+j],S[j])){ok=false;break}}if(ok){found=p;break}}
    if(found>0){m=Math.min(L.length-found,S.length);for(j=0;j<m;j++)enrich(S[j],L[found+j]);
      var head=[];for(j=0;j<found;j++)head.push(mk(L[j]));
      var tail=[];for(j=found+m;j<L.length;j++)tail.push(mk(L[j]));
      S=head.concat(S,tail);
    }else{
      /* 3) 系列として重ならない(順序が食い違う等): 1件ずつ既存と突き合わせ、未対応のものだけ追加 */
      var used={};
      L.forEach(function(x){var hit=-1;for(var i=0;i<S.length;i++){if(!used[i]&&koeChatEq(S[i],x)){hit=i;break}}if(hit>=0){used[hit]=1;enrich(S[hit],x)}else{S.push(mk(x))}});
    }
  }
  while(S.length>300)S.shift();
  window.__chatSeq=seq;window.__chatStore=S;return S;
}
function koeChatIsMine(c){var myUid=window.__myUserId||0;if(c.mine)return true;if(myUid&&Number(c.user_id)===myUid)return true;if(Number(c.user_id)>0)return false;return (window.__chatMine||[]).some(function(m){return m.text===c.text})}
/* 枠内の出来事(参加・退出・挙手・昇格・枠名変更など)をチャット欄にも時系列で表示する */
/* 枠主がコメントを禁止している場合は入力欄を閉じる(公式 comment_enabled) */

/* ===== 最終オンライン(「3分前」等)の解釈 ===== */
function koeParseLoginAgo(str){try{var t=String(str||"").trim();if(!t)return null;if(/オンライン中|たった今|^今$|just now|online/i.test(t))return 0;var m=t.match(/(\d+)\s*(秒|分|時間|日|週間|週|ヶ月|か月|カ月|年)/);if(!m)return null;var n=parseInt(m[1],10),u=m[2];if(u==="秒")return n/60;if(u==="分")return n;if(u==="時間")return n*60;if(u==="日")return n*1440;if(u==="週"||u==="週間")return n*10080;if(/月/.test(u))return n*43200;if(u==="年")return n*525600;return null}catch(e){return null}}
function koeOnlineState(str){var m=koeParseLoginAgo(str);if(m===null)return{cls:"",label:""};if(m<=5)return{cls:"online-dot",label:"いまオンライン"};if(m<=60)return{cls:"online-dot online-dot-recent",label:"最近オンライン("+String(str)+")"};return{cls:"online-dot online-dot-off",label:"最終オンライン "+String(str)}}
/* フォロー中の人の活動時間帯(集計のみ・個人ごとの履歴は保存しない) */
function koeActivityRecord(users){try{var K="koe_act_hours",D="koe_act_seen";var hours=JSON.parse(localStorage.getItem(K)||"null")||{};var seen=JSON.parse(localStorage.getItem(D)||"null")||{};var now=Date.now();Object.keys(seen).forEach(function(k){if(now-seen[k]>172800000)delete seen[k]});var added=0;(users||[]).forEach(function(u){var m=koeParseLoginAgo(u.login_status);if(m===null||m>720)return; /* 12時間以内の精度のあるものだけ */var t=new Date(now-m*60000);var h=t.getHours();var key=String(u.user_id)+"|"+t.getFullYear()+"-"+t.getMonth()+"-"+t.getDate()+"-"+h;if(seen[key])return;seen[key]=now;hours[h]=(hours[h]||0)+1;added++});localStorage.setItem(K,JSON.stringify(hours));localStorage.setItem(D,JSON.stringify(seen));return added}catch(e){return 0}}
function koeActivityHours(){try{return JSON.parse(localStorage.getItem("koe_act_hours")||"null")||{}}catch(e){return{}}}
async function koeActivityCollect(){var uid=(typeof myUserId!=="undefined"&&myUserId)||(typeof currentAccountId==="function"&&currentAccountId())||window.__myUserId||0;if(!uid){toast("ログイン情報が取得できません","error");return}var all=[],onlineNow=[];for(var p=1;p<=3;p++){var r=null;try{r=await callApi("get_followees",String(uid),String(p))}catch(e){}if(!r||!r.ok||!(r.users||[]).length)break;all=all.concat(r.users);if(r.users.length<20)break}var n=koeActivityRecord(all);all.forEach(function(u){var m=koeParseLoginAgo(u.login_status);if(m!==null&&m<=5)onlineNow.push(u)});window.__koeOnlineNow=onlineNow;koeActivityRender(all.length,onlineNow);return n}
function koeActivityRender(total,onlineNow){var box=document.getElementById("koeActHoursBox");if(!box)return;var h=koeActivityHours();var max=0,sum=0;for(var i=0;i<24;i++){max=Math.max(max,h[i]||0);sum+=h[i]||0}if(!sum){box.innerHTML='<div class="empty-msg" style="padding:6px 0;">まだデータがありません。「いま集計」を押すとフォロー中の人の最終オンラインから集計します(何度か集めると傾向が見えます)。</div>';return}var bars="";for(var i=0;i<24;i++){var v=h[i]||0,pct=max?Math.round(v/max*100):0;bars+='<div class="koe-act-col" title="'+i+'時: '+v+'"><div class="koe-act-bar" style="height:'+Math.max(pct,v?6:0)+'%"></div><div class="koe-act-lb">'+(i%6===0?i:"")+'</div></div>'}var peak=[];for(var i=0;i<24;i++)peak.push([i,h[i]||0]);peak.sort(function(a,b){return b[1]-a[1]});var top=peak.slice(0,3).filter(function(x){return x[1]>0}).map(function(x){return x[0]+"時台"}).join("・");var on=(onlineNow||window.__koeOnlineNow||[]);box.innerHTML='<div class="koe-act-chart">'+bars+'</div><div class="card-sub" style="text-align:center;margin-top:4px;">サンプル '+sum+' 件'+(top?'・多い時間帯: '+top:'')+(typeof total==="number"?'・フォロー中 '+total+'人':'')+'</div>'+(on.length?'<div class="card-sub" style="margin-top:6px;"><span class="online-dot"></span>いまオンライン('+on.length+'): '+on.slice(0,12).map(function(u){return '<a href="#" onclick="viewProfile('+Number(u.user_id)+');return false;">'+escapeHtml(u.name||("user "+u.user_id))+'</a>'}).join("、")+(on.length>12?" ほか":"")+'</div>':'')}


/* ===== プロフィール「詳細情報」: API が返す全項目を日本語ラベルで表示 ===== */
var KOE_PF_LABELS={is_friend_requester:"あなたに友達申請中",is_friend_requestee:"あなたから友達申請中",is_follower:"あなたをフォロー中",is_followee:"あなたがフォロー中",ticket_count:"応募口数",title_text:"キャンペーン",count_label_text:"ラベル",unit_text:"単位",info_url:"案内URL",has_receiver_user:"応援トーク受付者",is_banned:"応援トークBAN",sound_enabled:"サウンド",vibration_enabled:"バイブ",do_not_disturb_enabled:"おやすみモード",do_not_disturb_from:"おやすみ開始",do_not_disturb_to:"おやすみ終了",user_id:"ユーザーID",name:"名前",sex:"性別",gender:"性別",area_name:"地域",area_id:"地域ID",age:"年齢",comment:"自己紹介",suspend_flag:"凍結",liked_count:"もらったいいね",followee_count:"フォロー数",follower_count:"フォロワー数",friend_count:"友達数",login_status:"最終ログイン",login_status_with_unit:"最終オンライン",profile_picture_file_path:"アイコン",header_image_file_path:"ヘッダー画像",profile_voice_file_path:"ボイスプロフィール",feature:"特徴・フラグ",is_followed:"あなたをフォロー",is_following:"あなたがフォロー",is_blocking:"あなたをブロック中",is_blocked:"あなたがブロック中",is_friend:"友達",active_follows:"アクティブなフォロー",passive_follows:"フォロー返し待ち",total_free_coin:"無料コイン",total_paid_coin:"有料コイン",total_point:"ポイント",available_point:"利用可能ポイント",pending_point:"保留ポイント",is_sms_authenticated:"SMS認証",is_email_authenticated:"メール認証",age_verification_status:"年齢確認",settings:"公開設定",cheering_talk:"応援トーク",drawing_ticket:"抽選チケット",chat_id:"チャットID",chat_permission_level:"DM受付",talk_request_permission_level:"トークリクエスト受付",member_rank_id:"会員ランク",warning_count:"警告回数",is_in_penalty_period:"ペナルティ期間中",last_sign_in_at:"最終サインイン",decoration_item_id:"装飾アイテム",badge_image_file_path:"バッジ画像",is_follow_list_public:"フォロー一覧を公開",is_follower_list_public:"フォロワー一覧を公開",is_friend_list_public:"友達一覧を公開",is_gift_public:"ギフトを公開",is_good_talk_count_public:"グッドトーク数を公開",is_my_age_public:"年齢を公開",is_online_status_public:"オンライン状態を公開",is_read_receipt_public:"既読を公開",random_match_enabled:"ランダムマッチ",timeline_image_enabled:"タイムライン画像",height:"身長",weight:"体重",birthday:"誕生日",twitter_id:"X(Twitter) ID",line_id:"LINE ID",facebook_id:"Facebook ID",invite_code:"招待コード",is_birthday:"今日が誕生日",good_talk_count:"グッドトーク数",status:"ステータス",receiver_status:"受付状態",is_receiver:"応援トーク受付",price:"料金",coin_per_minute:"1分あたりコイン",count:"枚数",ticket_count:"チケット枚数",created_at:"作成日",updated_at:"更新日",id:"ID"};
function koePfVal(k,v){if(v===null||v===undefined)return"—";if(typeof v==="boolean")return v?"はい":"いいえ";if(typeof v==="number"){if(/^is_|_flag$|_enabled$|_public$|authenticated/.test(k))return v===1?"はい":v===0?"いいえ":String(v);if(k==="sex"||k==="gender")return v===1?"男性":v===2?"女性":String(v);if(/(^|_)id$|_at$|status$|level$|rank/.test(k))return String(v);return fmtNum(v)}if(typeof v==="string"){if(/file_path$/.test(k))return v?"あり":"—";return v===""?"—":v}return String(v)}
function koeProfileDetailHtml(p){try{var r=p&&p.raw_user;if(!r||typeof r!=="object")return"";var rows=[];var skip={name:1,comment:1,profile_picture_file_path:1,header_image_file_path:1};function add(k,v,pre){var label=KOE_PF_LABELS[k]||k;if(pre)label=pre+" › "+label;if(v&&typeof v==="object"&&!Array.isArray(v)){var ks=Object.keys(v);if(!ks.length){rows.push([label,"—"]);return}ks.forEach(function(kk){add(kk,v[kk],KOE_PF_LABELS[k]||k)});return}if(Array.isArray(v)){rows.push([label,v.length?v.map(function(x){return typeof x==="object"?JSON.stringify(x):String(x)}).join(", "):"—"]);return}rows.push([label,koePfVal(k,v)])}Object.keys(r).forEach(function(k){if(skip[k])return;add(k,r[k],"")});if(!rows.length)return"";var blocking=(r.is_blocking===true||Number(r.is_blocking)===1);var head=blocking?'<div class="card-sub" style="color:#f1436b;margin-bottom:6px;">⚠ この人はあなたをブロックしています（API確認）</div>':"";return '<details class="pv-detail"><summary>詳細情報（APIが返す全項目 '+rows.length+'件）</summary>'+head+'<table class="pv-detail-tbl">'+rows.map(function(x){return '<tr><td>'+escapeHtml(x[0])+'</td><td>'+escapeHtml(String(x[1]))+'</td></tr>'}).join("")+'</table><div class="card-sub" style="opacity:.6;margin-top:4px;">公式アプリが表示していない項目も含みます。値の意味が不明なものは英語キーのまま表示しています。</div></details>'}catch(e){return""}}


/* ===== 業者(量産)アカウント判定 =====
   実データで確認した特徴: アイコンのファイル名が16文字の英数字ランダム(通常は50桁のハッシュ)、
   名前が「単語+3桁数字」、フォロー/フォロワー/友達/いいねが全て0、自己紹介なし、SMS未認証、IDが直近で連番に近い。
   ここでは「疑い」を表示するだけで、ブロックは必ず人が確認して行う。 */
function koeSpamScore(u){try{if(!u)return{score:0,reasons:[]};var sc=0,rs=[];
  var icon=String(u.icon_url||u.profile_picture_file_path||"");var fn=icon.split("?")[0].split("/").pop()||"";
  if(/^[A-Za-z0-9]{16}\.(png|jpe?g|webp)$/i.test(fn)){sc+=3;rs.push("量産型アイコン名")}
  var nm=String(u.name||"");if(/^[^\s]{1,20}\d{3}$/.test(nm)&&!/^\d+$/.test(nm)){sc+=2;rs.push("名前が単語+3桁数字")}
  var has=function(k){return u[k]!==undefined&&u[k]!==null};
  if(has("follower_count")&&has("followee_count")&&Number(u.follower_count)===0&&Number(u.followee_count)===0){sc+=1;rs.push("フォロー0/フォロワー0")}
  if(has("friend_count")&&Number(u.friend_count)===0&&has("liked_count")&&Number(u.liked_count)===0){sc+=0.5}
  if(has("comment")&&!String(u.comment||"").trim()){sc+=0.5;rs.push("自己紹介なし")}
  var ru=u.raw_user||u;if(ru&&ru.is_sms_authenticated!==undefined&&!truthyJs(ru.is_sms_authenticated)){sc+=0.5}
  if(ru&&ru.settings&&ru.settings.random_match_enabled===true){sc+=0.5}
  var lv=sc>=5?"high":sc>=3?"mid":"";return{score:sc,level:lv,reasons:rs}}catch(e){return{score:0,reasons:[]}}}
function truthyJs(v){return v===true||v===1||v==="1"||v==="true"}
function koeSpamEnabled(){try{var v=localStorage.getItem("koe_spam_mark");return v===null?true:v==="1"}catch(e){return true}}
function koeSpamHide(){try{return localStorage.getItem("koe_spam_hide")==="1"}catch(e){return false}}
function koeSpamTag(u,compact){try{if(!koeSpamEnabled())return"";var r=koeSpamScore(u);if(!r.level)return"";var t=(r.level==="high"?"業者の可能性 高":"業者の可能性")+"："+r.reasons.join("・");
  return ' <span class="uid-tag koe-spam-tag koe-spam-'+r.level+'" title="'+escAttr(t)+'" data-nm="'+escAttr(String(u.name||""))+'" onclick="event.stopPropagation();koeSpamPrompt('+Number(u.user_id||0)+',this)">⚠ 業者?</span>'}catch(e){return""}}
async function koeSpamPrompt(uid,el){try{if(!uid)return;var t=el&&el.getAttribute("title")||"業者の可能性があります";var nm=el&&el.getAttribute("data-nm")||"";
  var m=document.createElement("div");m.className="modal";m.style.display="flex";
  m.innerHTML='<div class="modal-content small"><div class="modal-header"><span>業者アカウントの可能性</span><button class="modal-close koe-sp-x">✕</button></div><div class="modal-body">'
    +'<p class="page-desc" style="white-space:normal;">'+escapeHtml(nm?nm+"（ID:"+uid+"）":"ID:"+uid)+'</p>'
    +'<div class="card-sub" style="white-space:pre-wrap;margin:6px 0 10px;">'+escapeHtml(t)+'</div>'
    +'<p class="page-desc" style="white-space:normal;">業者垢だと確認できたら、共有BANリストに申請してください。申請時にアプリが相手の情報を取り直して判定材料を添えます（手入力の理由は送りません）。管理者が承認するとリストに反映され、その時点で各端末が自動でブロックします。申請だけではブロックしません。</p>'
    +'<div style="display:flex;gap:8px;margin-top:12px;flex-wrap:wrap;"><button type="button" class="btn-primary koe-sp-report" style="width:auto;">BANリストに申請する</button><button type="button" class="btn-secondary koe-sp-close" style="width:auto;">閉じる</button></div></div></div>';
  document.body.appendChild(m);var close=function(){try{m.remove()}catch(e){}};
  m.querySelector(".koe-sp-x").addEventListener("click",close);m.querySelector(".koe-sp-close").addEventListener("click",close);m.addEventListener("click",function(e){if(e.target===m)close()});
  m.querySelector(".koe-sp-report").addEventListener("click",async function(){var b=this;b.disabled=true;b.textContent="申請中…";var ok=await koeSpamReport(uid,t,nm);if(ok){close()}else{b.disabled=false;b.textContent="BANリストに申請する"}});
}catch(e){}}
/* 共有BANリスト(redredfast)へ「業者」として申請。理由コードは bot、詳細に検出した特徴を添える。reporter は自分の uid(ネイティブ側で固定) */
async function koeSpamReport(uid,reasonText,nm){try{var url=(typeof BANLIST_FIXED_URL!=="undefined"&&BANLIST_FIXED_URL)||"https://redredfast.com";
  /* 理由・証拠はアプリ(ネイティブ側)がその場で API から取得して判定する。画面上の文字列は送らない(偽造防止) */
  var r=await callApi("moderation_report_spam",url,String(uid));
  if(r&&r.ok){toast(r.duplicate?"この相手は既に申請されています。ご協力ありがとうございます":"申請しました。コミュニティへの貢献にありがとうございます！承認されると共有BANリストに反映されます");try{sfx("success")}catch(e){}return true}
  if(r&&r.error==="not_spam_like"){toast(r.message||"業者判定の条件を満たしていません","error");return false}
  if(r&&r.error==="rate_limited"){toast("申請が多すぎます。時間をおいてください","error");return false}
  if(r&&r.error==="cannot_report_self"){toast("自分自身は申請できません","error");return false}
  toast("申請に失敗しました"+(r&&(r.message||r.error)?"："+(r.message||r.error):""),"error");return false}catch(e){toast("申請に失敗しました","error");return false}}

/* ===== 削除された投稿の記録 =====
   表示した投稿を端末内に保存(直近600件)。あとで開いたときに 404(削除済み)なら、保存していた内容を表示し
   「削除された投稿」一覧にも残す。 */
function koePostCacheLoad(){try{return JSON.parse(localStorage.getItem("koe_seen_posts")||"{}")||{}}catch(e){return{}}}
function koePostCacheSave(m){try{var ks=Object.keys(m);if(ks.length>600){ks.sort(function(a,b){return (m[a].s||0)-(m[b].s||0)});ks.slice(0,ks.length-600).forEach(function(k){delete m[k]})}localStorage.setItem("koe_seen_posts",JSON.stringify(m))}catch(e){}}
function koeRememberPosts(posts){try{if(!Array.isArray(posts)||!posts.length)return;var m=koePostCacheLoad(),now=Date.now(),ch=false;posts.forEach(function(p){if(!p||!p.id)return;var k=String(p.id);if(m[k]&&m[k].t===(p.text||""))return;m[k]={u:Number(p.user_id)||0,n:p.name||"",i:p.icon_url||"",t:(p.text||"").slice(0,500),c:p.created_at||"",img:p.image_url||"",tk:p.is_talk?1:0,s:now};ch=true});if(ch)koePostCacheSave(m)}catch(e){}}
function koeDeletedLoad(){try{return JSON.parse(localStorage.getItem("koe_deleted_posts")||"[]")||[]}catch(e){return[]}}
function koeMarkDeleted(id){try{var m=koePostCacheLoad(),k=String(id),c=m[k];if(!c)return null;var d=koeDeletedLoad();if(!d.some(function(x){return String(x.id)===k})){d.unshift({id:k,u:c.u,n:c.n,i:c.i,t:c.t,c:c.c,img:c.img,d:Date.now()});if(d.length>300)d.length=300;localStorage.setItem("koe_deleted_posts",JSON.stringify(d))}return c}catch(e){return null}}
function koeAbsTime(v){try{if(v==null||v==="")return"";var sv=String(v),d=/^\d+$/.test(sv)?new Date(parseInt(sv,10)*(sv.length<=10?1e3:1)):new Date(sv);if(isNaN(d.getTime()))return"";var p2=function(n){return String(n).padStart(2,"0")};var now=new Date();var same=d.getFullYear()===now.getFullYear()&&d.getMonth()===now.getMonth()&&d.getDate()===now.getDate();return (same?"":(d.getMonth()+1)+"/"+d.getDate()+" ")+p2(d.getHours())+":"+p2(d.getMinutes())}catch(e){return""}}
/* 相対時間に絶対時刻を添える(設定「相対時間」オフのときは relTime 自体が絶対表記) */
function koeTimeLabel(v){try{var r=relTime(v);if("off"===localStorage.getItem("koe_reltime"))return r;var a=koeAbsTime(v);return a?r+' <span class="koe-abs-time">'+a+'</span>':r}catch(e){return relTime(v)}}
function koeRenderDeletedPosts(){try{var box=document.getElementById("koeDeletedPostsBox");if(!box)return;var d=koeDeletedLoad();if(!d.length){box.innerHTML='<div class="empty-msg" style="padding:6px 0;">まだありません。一度表示した投稿があとで削除されていた場合にここに残ります（投稿を開いたときに判定）。</div>';return}
  box.innerHTML=d.slice(0,100).map(function(x){return '<div class="card" style="align-items:flex-start;"><div onclick="viewProfile('+Number(x.u)+')" style="cursor:pointer;">'+avatarHtml(x.n,x.i)+'</div><div class="card-body"><div class="card-name">'+escapeHtml(x.n||("user "+x.u))+' <span class="uid-tag">ID:'+Number(x.u)+'</span> <span class="uid-tag koe-regbadge">削除済み</span></div><div class="card-sub" style="white-space:pre-wrap;word-break:break-word;opacity:.9;">'+escapeHtml(x.t||"(本文なし)")+'</div><div class="card-sub">投稿 '+escapeHtml(koeAbsTime(x.c)||relTime(x.c)||"?")+' ・ 削除確認 '+escapeHtml(koeAbsTime(x.d))+'</div></div></div>'}).join("")+(d.length>100?'<div class="card-sub" style="opacity:.6;">ほか '+(d.length-100)+' 件</div>':"")}catch(e){}}

function koeApplyCommentEnabled(v){try{if(v!==false&&v!==true)return;var i=document.getElementById("callChatInput"),b=document.getElementById("callChatSendBtn");if(!i)return;var was=i.disabled;i.disabled=(v===false);if(b)b.disabled=(v===false);i.placeholder=(v===false)?"この枠ではコメントが禁止されています":"コメントを送信(改行OK)";if(was!==i.disabled&&window.__chatNotifiedInit){koeChatSystem(v===false?"枠主がコメントを禁止しました":"コメントが許可されました")}}catch(e){}}
function koeChatSystem(text){try{if(!currentRoomId||!text)return;var a=window.__chatSys||(window.__chatSys=[]);var now=Date.now();if(a.length&&a[a.length-1].text===text&&now-a[a.length-1].addedAt<1500)return;a.push({text:String(text),addedAt:now});while(a.length>100)a.shift();koeChatRender()}catch(e){}}
function koeChatRender(){
  var log=document.getElementById("callChatLog");if(!log)return;
  var S=window.__chatStore||[],P=window.__chatPending||[],Y=window.__chatSys||[];
  var sig=S.map(function(c){return c.k+(c.explicit?"e":"")+(c.penalty?"p":"")}).join(",")+"#"+P.map(function(p){return p.id+(p.failed?"f":"")}).join(",")+"#"+Y.length;
  if(sig===log.__sig)return;log.__sig=sig;
  var atBottom=log.scrollHeight-log.scrollTop-log.clientHeight<50;
  /* コメントと出来事を受信時刻で並べる(同時刻ならコメントを先に) */
  var rows=[];S.forEach(function(c){var tags=(c.explicit?'<span class="koe-chat-tag koe-chat-tag-reg" title="運営により規制対象と判定されたコメント">規制対象</span>':'')+(c.penalty?'<span class="koe-chat-tag koe-chat-tag-pen" title="この人は現在ペナルティ期間中です">ペナルティ中</span>':'');
    var mine=koeChatIsMine(c),nm=mine?"あなた":(c.name||"user "+c.user_id),ic=c.icon_url||(mine?(window.__myIcon||""):"");
    var av=(typeof avatarHtml==="function")?avatarHtml(c.name||nm,ic):"";
    rows.push({t:c.addedAt||0,o:0,h:'<div class="koe-chat-row'+(c.explicit?' koe-chat-reg':'')+(mine?' koe-chat-mine':'')+'"'+(Number(c.user_id)>0?' data-uid="'+Number(c.user_id)+'"':'')+'>'+av+'<div class="koe-chat-msg"><div class="koe-chat-nm">'+escapeHtml(nm)+tags+'</div><div class="koe-chat-tx">'+escapeHtml(c.text)+'</div></div></div>'})});
  Y.forEach(function(y){rows.push({t:y.addedAt||0,o:1,h:'<div class="koe-chat-sys">'+escapeHtml(y.text)+'</div>'})});
  rows.sort(function(a,b){return a.t-b.t||a.o-b.o});
  log.innerHTML=rows.map(function(r){return r.h}).join("")
    +P.map(function(p){return '<div class="koe-chat-row koe-chat-mine koe-chat-pending" data-pid="'+p.id+'">'+((typeof avatarHtml==="function")?avatarHtml("あなた",window.__myIcon||""):"")+'<div class="koe-chat-msg"><div class="koe-chat-nm">あなた <span style="opacity:.5;font-size:.8em;">'+(p.failed?"(反映を確認できませんでした)":"送信中…")+'</span></div><div class="koe-chat-tx">'+escapeHtml(p.text)+'</div></div></div>'}).join("");
  if(atBottom)log.scrollTop=log.scrollHeight;
}
async function reloadRoomComments(listOverride){
  if(!currentRoomId)return;
  var roomAtStart=currentRoomId;var list=null;
  if(Array.isArray(listOverride)){list=listOverride}
  else{
    /* RTDB のコメントストリームが生きている間は REST ポーリングを間引く(公式は REST で取得しない) */
    if(koeRtdbCommentsLive()&&!window.__rtdbForce)return;
    var r=null;try{r=await callApi("get_room_comments",String(roomAtStart))}catch(e){return}
    if(!currentRoomId||String(currentRoomId)!==String(roomAtStart))return; /* 取得中に退出/別枠へ */
    if(!r||!r.ok||!Array.isArray(r.comments))return;
    list=r.comments;
  }
  var before=(window.__chatStore||[]).length;
  var S=koeChatMerge(list);
  var added=S.filter(function(c){return !c.__seen});
  /* 送信中(pending)の自分の発言が届いたら pending から外し、その項目を自分の発言として確定 */
  try{var P=window.__chatPending||[];if(P.length){var now=Date.now();window.__chatPending=P.filter(function(p){var hit=added.find(function(c){return !c.mine&&c.text===p.text&&(!(Number(c.user_id)>0)||Number(c.user_id)===(window.__myUserId||0))});if(hit){hit.mine=true;return false}if(p.failed)return now-p.ts<40000;return true})}}catch(e){}
  /* 通話記録 */
  try{if(window.__callSession){if(!window.__callSession.chat)window.__callSession.chat=[];added.forEach(function(c){window.__callSession.chat.push({t:Date.now()-window.__callSession.startMs,uid:Number(c.user_id),name:c.name||"user "+c.user_id,text:c.text});if(window.__callSession.chat.length>300)window.__callSession.chat.shift()})}}catch(e){}
  /* 新着通知(初回スナップショットと自分の発言は除く) */
  try{var first=!window.__chatNotifiedInit;var fresh=added.filter(function(c){return !first&&!koeChatIsMine(c)});window.__chatNotifiedInit=true;
    if(fresh.length){var panel=document.getElementById("callChatPanel"),open=panel&&panel.style.display!=="none";
      if(!open){window.__chatUnread=(window.__chatUnread||0)+fresh.length;var tg=document.getElementById("callChatToggle");if(tg){var bd=tg.querySelector(".callv2-badge");if(!bd){bd=document.createElement("span");bd.className="callv2-badge";tg.appendChild(bd)}bd.textContent=window.__chatUnread>99?"99+":String(window.__chatUnread);bd.style.display="flex"}}
      if(!open)fresh.slice(-3).forEach(function(c){koeChatBubble((c.name||("user "+c.user_id))+(c.explicit?"（規制対象）":""),c.text,c.icon_url)});
      try{if(localStorage.getItem("koe_callchat_sound")!=="0")sfx("message")}catch(e){}}
  }catch(e){}
  added.forEach(function(c){c.__seen=true});
  koeChatRender();
}
async function sendRoomComment(){
  sfx("send");
  var input=document.getElementById("callChatInput"),text=(input.value||"").trim();
  if(!text||!currentRoomId)return;
  var room=currentRoomId;input.value="";try{input.style.height="auto"}catch(e){}
  var pend={text:text,ts:Date.now(),id:"p"+Date.now()+"_"+Math.floor(Math.random()*1e4),failed:false};
  (window.__chatPending||(window.__chatPending=[])).push(pend);
  (window.__chatMine||(window.__chatMine=[])).push({text:text,ts:pend.ts});if(window.__chatMine.length>50)window.__chatMine.shift();
  koeChatRender();try{var lg=document.getElementById("callChatLog");if(lg)lg.scrollTop=lg.scrollHeight}catch(e){}
  var res=null;try{res=await callApi("send_room_comment",room,text)}catch(e){res={ok:false,error:String(e)}}
  if(!currentRoomId||String(currentRoomId)!==String(room)){window.__chatPending=(window.__chatPending||[]).filter(function(x){return x.id!==pend.id});return}
  if(res&&res.ok){
    var tries=0;
    var tick=async function(){
      tries++;
      var still=(window.__chatPending||[]).some(function(x){return x.id===pend.id&&!x.failed});
      if(!still||!currentRoomId||String(currentRoomId)!==String(room))return;
      if(tries>6){pend.failed=true;koeChatRender();try{callLog("チャット: 送信は成功したが10秒以内に反映を確認できず: "+text.slice(0,40))}catch(e){}return}
      window.__rtdbForce=true;try{await reloadRoomComments()}catch(e){}finally{window.__rtdbForce=false}
      setTimeout(tick,tries===1?600:1800);
    };
    tick();
  }else{
    window.__chatPending=(window.__chatPending||[]).filter(function(x){return x.id!==pend.id});
    window.__chatMine=(window.__chatMine||[]).filter(function(x){return !(x.text===text&&x.ts===pend.ts)});
    koeChatRender();
    try{var log=document.getElementById("callChatLog");var err=document.createElement("div");err.style.color="var(--danger,#e66)";var msg=res&&(res.message||res.error||(res.body&&(res.body.message||res.body.error_message||res.body.error))||(res.status?"HTTP "+res.status:""));if(res&&res.session_expired)msg="ログインの有効期限が切れています";err.textContent="送信失敗"+(msg?"："+String(msg).slice(0,80):"");log.appendChild(err);log.scrollTop=log.scrollHeight}catch(e){}
    try{input.value=text}catch(e){}
    try{callLog("チャット送信失敗: "+JSON.stringify(res).slice(0,200))}catch(e){}
  }
}
async function joinViaCard(cardEl,joinFn){if(isJoiningCall)return;isJoiningCall=!0;const originalHtml=cardEl.innerHTML;cardEl.classList.add("card-joining"),cardEl.innerHTML='<div class="joining-spinner"></div><span>参加処理中...</span>';try{await joinFn()}finally{isJoiningCall=!1,cardEl.classList.remove("card-joining"),cardEl.innerHTML=originalHtml}}function onJoinSuccess(result){try{var __rn=result&&result.room&&(result.room.description||result.room.title)||document.getElementById("callRoomName")&&document.getElementById("callRoomName").textContent||"通話";csInit(__rn,currentRoomOwnerId||result&&result.owner_user_id||window.__callOwnerUid||0)}catch(e){}if(currentRoomId=result.room_id||null,document.getElementById("raiseHandRow").style.display=currentRoomId?"block":"none",window.__prevApplicantUids=null,window.__prevSpeakerUids=null,window.__prevAllUids=null,window.__prevMuteState=null,window.__justRaisedUids=new Set,window.__justPromotedUids=new Set,updateCallRoster(result),renderApplicants(null===currentRoomOwnerId&&result.speaker_applicants||[]),currentRoomId&&startApplicantPolling(),result.call&&startInWindowCall(result.call),currentRoomId)try{startRoomCommentPolling()}catch(e){}{const ci=document.querySelector('.rail-item[data-view="call"]');ci&&ci.classList.add("in-call")}try{const chk=document.getElementById("autoRaiseHandChk");chk&&chk.checked&&currentRoomId&&null!==currentRoomOwnerId&&setTimeout(()=>{try{doRaiseHand()}catch(e){}},1500)}catch(e){}}function startRoomListAutoRefresh(){stopRoomListAutoRefresh(),roomListRefreshTimer=setInterval(()=>{isJoiningCall||currentRoomId||loadGroupRooms()},2e4)}function stopRoomListAutoRefresh(){roomListRefreshTimer&&(clearInterval(roomListRefreshTimer),roomListRefreshTimer=null)}function startApplicantPolling(){if(stopApplicantPolling(),!currentRoomId)return;const poll=async()=>{if(!currentRoomId)return void stopApplicantPolling();if(window.__rtdbAlive&&Date.now()-window.__rtdbAlive<45000){window.__pollSkip=(window.__pollSkip||0)+1;if(window.__pollSkip%5!==0)return}const res=await callApi("refresh_room_state",currentRoomOwnerId,currentRoomId?String(currentRoomId):"");if(res&&res.ok&&(res.room_id===null||res.room_id===undefined||res.room_id===0)){window.__roomGoneCount=(window.__roomGoneCount||0)+1;if(window.__roomGoneCount>=2){window.__roomGoneCount=0;onRoomClosed()}return}window.__roomGoneCount=0;if(res&&res.ok){updateCallRoster(res);renderApplicants(null===currentRoomOwnerId&&res.speaker_applicants||[]);try{koeSyncPublish(false)}catch(e){}try{window.__koeSyncSubs&&window.__koeSyncSubs()}catch(e){}try{koeApplyCommentEnabled(res.comment_enabled)}catch(e){}try{if(res.title){var rn=document.getElementById("callRoomName");if(rn&&rn.textContent!==res.title){var was=rn.textContent;rn.textContent=res.title;if(was&&was!=="-"&&was!=="通話"){toast("枠名が変更されました: "+res.title);try{callLog("枠名変更: "+res.title)}catch(e){}}}}}catch(e){}}};poll(),window.__pollRoomState=poll,applicantPollTimer=setInterval(poll,1000)}function stopApplicantPolling(){applicantPollTimer&&(clearInterval(applicantPollTimer),applicantPollTimer=null)}function onRoomClosed(){window.__roomGoneCount=0;try{stopApplicantPolling()}catch(e){}try{toast(" この枠は閉じられました")}catch(e){}try{sfx("leave")}catch(e){}try{callLog("枠が閉じられました(自動検知)")}catch(e){}try{leaveInWindowCall()}catch(e){}try{loadGroupRooms()}catch(e){}}function updateCallRoster(res){const applicants=res&&res.speaker_applicants||[],speakers=res&&res.speakers||[],listeners=res&&res.listeners||[],myUid=window.__myUserId||0,ownerUid=res&&res.owner_user_id||window.__callOwnerUid||0,appUids=new Set(applicants.map(a=>Number(a.userId||a.user_id)).filter(Boolean)),spkUids=new Set(speakers.map(a=>Number(a.user_id)).filter(Boolean)),nameOf=(arr,uid)=>{const x=arr.find(y=>Number(y.userId||y.user_id)===uid);return x&&x.name||"user "+uid};window.__prevApplicantUids&&appUids.forEach(uid=>{window.__prevApplicantUids.has(uid)||uid===myUid||(toast(" "+nameOf(applicants,uid)+" さんが手を挙げました"),window.__justRaisedUids.add(uid),setTimeout(()=>{try{window.__justRaisedUids.delete(uid),updateCallGrid()}catch(e){}},6e3))}),window.__prevSpeakerUids&&spkUids.forEach(uid=>{window.__prevSpeakerUids.has(uid)||uid===ownerUid||(toast(uid===myUid?" あなたが発言できるようになりました":" "+nameOf(speakers,uid)+" さんが発言者になりました"),window.__justPromotedUids.add(uid),setTimeout(()=>{try{window.__justPromotedUids.delete(uid),updateCallGrid()}catch(e){}},6e3))}),window.__prevApplicantUids=appUids,window.__prevSpeakerUids=spkUids,window.__handRaisedUids=appUids,window.__roomRoster={speakers:speakers,listeners:listeners,applicants:applicants,owner:ownerUid};(function(){try{var allNow=new Set();(speakers||[]).forEach(function(x){var u=Number(x.user_id||x.userId);if(u)allNow.add(u)});(listeners||[]).forEach(function(x){var u=Number(x.user_id||x.userId);if(u)allNow.add(u)});(applicants||[]).forEach(function(x){var u=Number(x.userId||x.user_id);if(u)allNow.add(u)});var nameAll=function(uid){var n=nameOf(speakers,uid);if(n!=="user "+uid)return n;n=nameOf(listeners,uid);if(n!=="user "+uid)return n;n=nameOf(applicants,uid);return n};if(window.__prevAllUids){allNow.forEach(function(uid){if(!window.__prevAllUids.has(uid)&&uid!==myUid&&uid!==ownerUid){toast("👋 "+nameAll(uid)+" さんが参加しました");try{sfx("join")}catch(e){}try{csEvent("join",uid,nameAll(uid))}catch(e){}}});window.__prevAllUids.forEach(function(uid){if(!allNow.has(uid)&&uid!==myUid){var lname=(window.__callSession&&window.__callSession.seen&&window.__callSession.seen[uid]&&window.__callSession.seen[uid].name)||("user "+uid);toast("🚪 "+lname+" さんが退出しました");try{csEvent("leave",uid,lname)}catch(e){}}})}window.__prevAllUids=allNow;try{if(allNow.size>0){var __mc=document.getElementById("callMemberCount");if(__mc)__mc.textContent=String(allNow.size)}}catch(e){}}catch(e){}})();try{(speakers||[]).concat(listeners||[],applicants||[]).forEach(function(u){csSeen(Number(u.user_id||u.userId),u.name,u.icon_url)})}catch(e){}try{if(window.__prevApplicantUids)window.__prevApplicantUids.forEach(function(uid){if(!appUids.has(uid)){if(spkUids.has(uid))csEvent("promote",uid,nameOf(speakers,uid));else csEvent("lower",uid,nameOf(applicants,uid))}});appUids.forEach(function(uid){if(!window.__prevApplicantUids||!window.__prevApplicantUids.has(uid))csEvent("raise",uid,nameOf(applicants,uid))});var __curMute={};(speakers||[]).forEach(function(u){__curMute[Number(u.user_id)]=!!u.is_mute});if(window.__prevMuteState){var __myU=window.__myUserId||0;Object.keys(__curMute).forEach(function(uid){var was=window.__prevMuteState[uid];if(was!==undefined&&was!==__curMute[uid]&&Number(uid)!==__myU)csEvent(__curMute[uid]?"mute":"unmute",Number(uid),nameOf(speakers,Number(uid)))})}window.__prevMuteState=__curMute}catch(e){}const sig=JSON.stringify({s:speakers.map(x=>Number(x.user_id)).sort(),l:listeners.map(x=>Number(x.user_id)).sort(),a:Array.from(appUids).sort(),o:ownerUid});if(sig!==window.__rosterSig){window.__rosterSig=sig;try{updateCallGrid()}catch(e){}}try{updateRaiseHandButton()}catch(e){}}function updateHandRaised(applicants){updateCallRoster({speaker_applicants:applicants})}function renderApplicants(applicants){applicants=applicants||[];const box=document.getElementById("applicantsBox");applicants.length?box.innerHTML='<div class="section-divider" style="margin-top:8px;"><svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M7 11V6a1.5 1.5 0 0 1 3 0v4m0 0V4.5a1.5 1.5 0 0 1 3 0V10m0 0V6a1.5 1.5 0 0 1 3 0v6m0-2a1.5 1.5 0 0 1 3 0v4a6 6 0 0 1-6 6h-1a6 6 0 0 1-5.4-3.4L4.5 13a1.6 1.6 0 0 1 2.8-1.5"/></svg> 挙手中のユーザー</div>'+applicants.map(a=>{const uid=a.userId||a.user_id;return`\n      <div class="participant">\n        <span style="display:flex;align-items:center;gap:8px;min-width:0;">\n          <span class="cp-av">${escapeHtml((a.name||"user "+uid).charAt(0).toUpperCase())}</span>\n          <span class="cp-name">${escapeHtml(a.name||"user "+uid)} <span class="uid-tag">ID:${uid}</span></span>\n        </span>\n        <span style="flex-shrink:0;">\n          <button class="mini-btn approve" onclick="doApprove(${uid})">許可</button>\n          <button class="mini-btn reject" onclick="doReject(${uid})">拒否</button>\n        </span>\n      </div>`}).join(""):box.innerHTML=""}async function refreshRoomStateNow(){if(currentRoomId)try{const res=await callApi("refresh_room_state",currentRoomOwnerId,currentRoomId?String(currentRoomId):"");if(res&&res.ok){updateCallRoster(res);renderApplicants(null===currentRoomOwnerId&&res.speaker_applicants||[]);try{koeSyncPublish(false)}catch(e){}try{window.__koeSyncSubs&&window.__koeSyncSubs()}catch(e){}}}catch(e){}}function updateRaiseHandButton(){const btn=document.getElementById("callRaiseHandBtn");if(!btn)return;const myUid=window.__myUserId||0;if(!currentRoomId||!myUid||null===currentRoomOwnerId)return void(btn.style.display="none");const r=window.__roomRoster,ownerUid=window.__callOwnerUid||r&&r.owner||0,isSpeaker=r&&r.speakers&&r.speakers.some(x=>Number(x.user_id)===myUid),isApplicant=r&&r.applicants&&r.applicants.some(x=>Number(x.userId||x.user_id)===myUid);if(myUid===ownerUid||isSpeaker)return void(btn.style.display="none");btn.style.display="";const span=btn.querySelector("span");isApplicant?(btn.classList.add("active"),span&&(span.textContent=" 挙手を取り下げる")):(btn.classList.remove("active"),span&&(span.textContent="手を挙げる"))}async function doLowerHand(){if(!currentRoomId)return;const myUid=window.__myUserId||0,r=window.__roomRoster;myUid&&r&&(r.applicants=(r.applicants||[]).filter(a=>Number(a.userId||a.user_id)!==myUid),r.listeners.some(x=>Number(x.user_id)===myUid)||(r.listeners=r.listeners.concat([{user_id:myUid,name:"あなた",icon_url:window.__myIcon||""}])),window.__handRaisedUids&&window.__handRaisedUids.delete(myUid),updateCallGrid());try{updateRaiseHandButton()}catch(e){}setCallStatus("挙手を取り下げました");(await callApi("lower_hand",currentRoomId)).ok||setCallStatus("取り下げに失敗しました"),refreshRoomStateNow()}async function doRaiseHand(){if(!currentRoomId)return;const myUid=window.__myUserId||0,r=window.__roomRoster;if(r&&r.applicants&&r.applicants.some(a=>Number(a.userId||a.user_id)===myUid))return doLowerHand();myUid&&r&&!r.applicants.some(a=>Number(a.userId||a.user_id)===myUid)&&(r.speakers=r.speakers.filter(x=>Number(x.user_id)!==myUid),r.listeners=r.listeners.filter(x=>Number(x.user_id)!==myUid),r.applicants=r.applicants.concat([{user_id:myUid,name:"あなた",icon_url:window.__myIcon||""}]),window.__handRaisedUids&&window.__handRaisedUids.add(myUid),updateCallGrid()),setCallStatus("挙手しました");(await callApi("raise_hand",currentRoomId)).ok||setCallStatus("挙手に失敗しました"),refreshRoomStateNow()}async function doApprove(userId){if(!currentRoomId)return;const uid=Number(userId),r=window.__roomRoster;if(r){const a=r.applicants.find(x=>Number(x.userId||x.user_id)===uid);r.applicants=r.applicants.filter(x=>Number(x.userId||x.user_id)!==uid),r.speakers.some(x=>Number(x.user_id)===uid)||(r.speakers=r.speakers.concat([{user_id:uid,name:a&&a.name||"user "+uid,icon_url:a&&a.icon_url||""}])),window.__handRaisedUids&&window.__handRaisedUids.delete(uid),updateCallGrid(),renderApplicants(r.applicants)}setCallStatus("発言を許可しました");try{window.__koeSyncSubs&&window.__koeSyncSubs()}catch(e){}await callApi("approve_speaker",currentRoomId,userId);await refreshRoomStateNow();try{window.__koeSyncSubs&&window.__koeSyncSubs()}catch(e){}}async function doReject(userId){if(!currentRoomId)return;const uid=Number(userId),r=window.__roomRoster;if(r){const a=r.applicants.find(x=>Number(x.userId||x.user_id)===uid);r.applicants=r.applicants.filter(x=>Number(x.userId||x.user_id)!==uid),a&&!r.listeners.some(x=>Number(x.user_id)===uid)&&(r.listeners=r.listeners.concat([{user_id:uid,name:a.name,icon_url:a.icon_url}])),window.__handRaisedUids&&window.__handRaisedUids.delete(uid),updateCallGrid(),renderApplicants(r.applicants)}setCallStatus("挙手を拒否しました");try{window.__koeSyncSubs&&window.__koeSyncSubs()}catch(e){}await callApi("reject_speaker",currentRoomId,userId);await refreshRoomStateNow()}async function loadModerationSettings(){const result=await callApi("get_moderation_settings");result.ok&&result.settings&&(document.getElementById("autoApproveChk").checked=!!result.settings.auto_approve,document.getElementById("autoRejectChk").checked=!!result.settings.auto_reject,document.getElementById("autoRaiseHandChk").checked=!!result.settings.auto_raise_hand)}async function saveModerationSettings(){const status=document.getElementById("moderationStatus"),result=await callApi("set_moderation_settings",document.getElementById("autoApproveChk").checked,document.getElementById("autoRejectChk").checked,document.getElementById("autoRaiseHandChk").checked);status.textContent=result.ok?"保存しました(次回参加時から適用)":"保存失敗"}async function joinGroupRoom(ownerUserId){if(!await ensureMicPermission()){setCallStatus("マイクが許可されていません。設定→権限から許可してください。");try{checkAppPermissions()}catch(e){}return}currentRoomOwnerId=ownerUserId,setCallStatus("トークルームを確認しています...");const result=await callApi("join_call",ownerUserId);result.ok?(setCallStatus("接続完了"),onJoinSuccess(result)):"room_not_found"===result.error||"no_target_room"===result.error?(setCallStatus(result.message||"この枠は終了したようです。一覧を更新しました。"),loadGroupRooms()):setCallStatus(`参加失敗: ${koeErrMsg(result)}`)}async function joinRoomById(roomId,ownerUserId){if(!await ensureMicPermission()){setCallStatus("マイクが許可されていません。設定→権限から許可してください。");try{checkAppPermissions()}catch(e){}return}currentRoomOwnerId=ownerUserId||null,setCallStatus("枠を確認しています...");const result=await callApi("join_room_by_id",String(roomId));if(result.ok){setCallStatus("接続完了");onJoinSuccess(result);return}if("room_closed"===result.error){setCallStatus("この枠は終了しています");toast("この枠は終了しています","error");loadGroupRooms();return}if(ownerUserId&&("room_not_found"===result.error||"no_target_room"===result.error)){/* room_id で見つからなければ主催者IDでも試す */const r2=await callApi("join_call",String(ownerUserId));if(r2.ok){setCallStatus("接続完了");onJoinSuccess(r2);return}}setCallStatus(result.message||"この枠は終了したようです。");toast(result.message||"この枠は終了したようです","error");loadGroupRooms()}
async function joinOwnRoom(){if(window.__joiningRoom)return;window.__joiningRoom=!0;setTimeout(function(){window.__joiningRoom=!1},3500);if(!await ensureMicPermission()){setCallStatus("マイクが許可されていません。設定→権限から許可してください。");try{checkAppPermissions()}catch(e){}return}currentRoomOwnerId=null,setCallStatus("自分のトークルームを確認しています...");const result=await callApi("join_call",null);result.ok?(setCallStatus("接続完了"),document.getElementById("createRoomRow").style.display="none",onJoinSuccess(result)):"no_own_room"===result.error?(setCallStatus(result.message),document.getElementById("createRoomRow").style.display="flex"):setCallStatus(`参加失敗: ${koeErrMsg(result)}`)}async function doCreateRoom(){const desc=document.getElementById("createRoomDesc").value.trim(),btn=document.getElementById("createRoomBtn");
if(desc.length<1||desc.length>20){setCallStatus("枠のタイトルは1〜20文字で入力してください");try{toast("枠のタイトルは1〜20文字で入力してください","error")}catch(e){}return}const __pub=document.getElementById("createRoomPublic"),__cmt=document.getElementById("createRoomComment");const isPub=!__pub||__pub.checked,cmtOn=!__cmt||__cmt.checked;btn.disabled=!0,setCallStatus("通話ルームを作成しています...");const result=await callApi("create_room",desc,isPub,cmtOn);btn.disabled=!1;if(result.ok){setCallStatus("ルームを作成して通話を開始しました"),document.getElementById("createRoomRow").style.display="none",currentRoomOwnerId=null,onJoinSuccess(result);
/* コメント禁止で作成した場合は作成直後に反映(公式は作成後に切替APIを呼ぶ) */
if(!cmtOn){try{const rid=result.room_id||window.currentRoomId;if(rid){const sw=await callApi("room_switch_comment_enabled",String(rid),false);if(!sw||sw.ok===false)toast(koeErrMsg(sw),"error")}}catch(e){}}
return}
if(result.existing_room_id){setCallStatus("すでに開いている自分の枠に接続しています...");const r2=await callApi("join_room_by_id",String(result.existing_room_id));if(r2&&r2.ok){setCallStatus("既存の枠に接続しました"),document.getElementById("createRoomRow").style.display="none",currentRoomOwnerId=null,onJoinSuccess(r2);return}
setCallStatus("すでに開いている自分の枠があります。枠を閉じてから作成してください。");return}
setCallStatus(`作成失敗: ${(result.message||result.body||result.error||"")+""}`.slice(0,220))}async function joinOtherRoom(){if(window.__joiningRoom)return;window.__joiningRoom=!0;setTimeout(function(){window.__joiningRoom=!1},3500);const el0=document.getElementById("otherUserId");if(!el0)return;const uid=el0.value.trim();if(!uid)return void toast("user_idを入力してください","error");currentRoomOwnerId=parseInt(uid,10),setCallStatus(`user_id=${uid} のトークルームを確認しています...`);const result=await callApi("join_call",parseInt(uid,10));result.ok?(setCallStatus("接続完了"),onJoinSuccess(result)):setCallStatus(`参加失敗: ${koeErrMsg(result)}`)}function setCallStatus(text){let statusEl=document.getElementById("callStatusLine");statusEl||(statusEl=document.createElement("div"),statusEl.id="callStatusLine",statusEl.className="empty-msg",statusEl.style.padding="8px 0",document.getElementById("callList").insertAdjacentElement("beforebegin",statusEl)),statusEl.textContent=text}let skRoom=null,skMe=null,skLocalStream=null,skMuted=!1,skMyPub=null,audioCtx=null,callAnalysers=[],callSpeakLoop=null,skCurrentRoomId=null,skIsOwner=!1;const CALL_LS_MIC="koetomo_call_mic_device_id",CALL_LS_SPK="koetomo_call_speaker_device_id";function callLog(msg){const el=document.getElementById("callLog");el.textContent+=`[${(new Date).toLocaleTimeString()}] ${msg}\n`,el.scrollTop=el.scrollHeight}function callSetStatus(msg){try{var el=document.getElementById("callStatus");var cv=document.querySelector(".callv2");var connected=cv&&cv.classList.contains("connected");/* 接続後は進行状況メッセージを出さない(エラーのみ) */if(connected&&msg&&!/エラー|失敗|切断/.test(msg))msg="";el.textContent=msg||"";el.style.display=msg?"":"none"}catch(e){}callLog(msg)}let __callSpeakerMuted=!1;function toggleCallSpeaker(){__callSpeakerMuted=!__callSpeakerMuted,document.querySelectorAll("#remoteAudios audio").forEach(a=>{a.muted=__callSpeakerMuted});const b=document.getElementById("callSpeakerBtn");b&&b.classList.toggle("muted",__callSpeakerMuted);try{var __ss=b&&b.querySelector("span");if(__ss)__ss.textContent=__callSpeakerMuted?"スピーカーOFF":"スピーカー"}catch(e){}toast(__callSpeakerMuted?"相手の音声をOFFにしました":"相手の音声をONにしました")}function toggleCallPanel(panelId,btnId){const panel=document.getElementById(panelId),willShow="none"===panel.style.display||!panel.style.display,cp=document.getElementById("callChatPanel");cp&&(cp.style.display="none");const sp=document.getElementById("callSettingsPanel");if(sp&&(sp.style.display="none"),["callChatToggle","callSettingsToggle"].forEach(id=>{const e=document.getElementById(id);e&&e.classList.remove("active")}),willShow){panel.style.display="block";const btn=document.getElementById(btnId);btn&&btn.classList.add("active")}try{var __cv=document.querySelector(".callv2");if(__cv)__cv.classList.toggle("chat-open",willShow&&panelId==="callChatPanel");if(willShow&&panelId==="callChatPanel"){var __lg=document.getElementById("callChatLog");if(__lg)__lg.scrollTop=__lg.scrollHeight}}catch(e){}}
/* 通話中チャット入力欄: 行数に合わせて自動で高さを変える */
(function(){try{var t=document.getElementById("callChatInput");if(!t||t.__grow)return;t.__grow=true;var g=function(){t.style.height="auto";t.style.height=Math.min(t.scrollHeight,130)+"px"};t.addEventListener("input",g);t.addEventListener("focus",g)}catch(e){}})();function callAvatarColor(uid){const colors=["#7c5cff","#e84d9b","#2AC1C7","#f5a623","#22C55E","#268aff","#ff6b6b","#14b8a6"];return colors[Math.abs(0|uid)%colors.length]}function hexToRgbTriple(hex){try{hex=String(hex).replace("#","");if(hex.length===3)hex=hex.split("").map(function(c){return c+c}).join("");var n=parseInt(hex,16);return(n>>16&255)+","+(n>>8&255)+","+(n&255)}catch(e){return"42,193,199"}}function applySpeakIndicator(){try{var col=localStorage.getItem("koe_speak_color")||"#2AC1C7";document.documentElement.style.setProperty("--speak-rgb",hexToRgbTriple(col));document.body.classList.toggle("speak-no-pulse",localStorage.getItem("koe_speak_pulse")==="0");var glow=localStorage.getItem("koe_speak_glow")||"normal";document.body.classList.remove("speak-glow-soft","speak-glow-strong");if(glow==="soft")document.body.classList.add("speak-glow-soft");else if(glow==="strong")document.body.classList.add("speak-glow-strong")}catch(e){}}/* 上部の緑パルス(通話ルーム数)タップで詳細を表示 */
window.__koeShowLivePulseDetail=function(){try{var r=window.__koeLastPulse;var m=document.getElementById("livePulseModal");if(!m){m=document.createElement("div");m.id="livePulseModal";m.className="modal";m.innerHTML='<div class="modal-content small"><div class="modal-header"><span>いまの通話状況</span><button class="modal-close" id="livePulseClose">✕</button></div><div class="modal-body" id="livePulseBody"></div></div>';document.body.appendChild(m);m.addEventListener("click",function(e){if(e.target===m)m.style.display="none"});m.querySelector("#livePulseClose").addEventListener("click",function(){m.style.display="none"})}var b=m.querySelector("#livePulseBody");if(!r||!r.ok){b.innerHTML='<div class="empty-msg">まだ取得できていません。少し待ってからもう一度タップしてください。</div>'}else{var rooms=r.open_rooms||0,sp=r.speakers||0,ls=r.listeners||0,box=function(l,v){return'<div style="flex:1;min-width:80px;text-align:center;padding:10px 6px;border-radius:12px;background:var(--bg-input,#1c1c1c);"><b style="display:block;font-size:20px;">'+v+'</b><span style="font-size:12px;opacity:.75;">'+l+'</span></div>'};var top=(r.top_rooms||[]).map(function(x){return'<div class="card" style="display:block;cursor:pointer;" onclick="try{document.getElementById(\'livePulseModal\').style.display=\'none\';showPage(\'call\')}catch(e){}"><div class="card-body" style="display:flex;justify-content:space-between;align-items:center;gap:8px;"><span class="card-name" style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">'+escapeHtml(x.title||"ルーム")+'</span><span class="card-sub" style="white-space:nowrap;">'+(x.speaker_count||0)+'人が話中 ・ '+(x.listener_count||0)+'人が聴取</span></div></div>'}).join("");b.innerHTML='<p class="page-desc" style="margin:0 0 8px;">ヘッダーの緑の数字は、いま開いている通話ルームの数です。'+(r.updated_at?' (更新: '+new Date(r.updated_at).toLocaleTimeString()+')':'')+'</p><div style="display:flex;gap:8px;">'+box("通話ルーム",rooms)+box("話している人",sp)+box("聴いている人",ls)+'</div>'+(top?'<div class="card-sub" style="margin:12px 0 4px;font-weight:600;">人が多いルーム</div>'+top:'')+'<button class="btn-primary" style="margin-top:12px;" onclick="try{document.getElementById(\'livePulseModal\').style.display=\'none\';showPage(\'call\')}catch(e){}">グループ通話を開く</button>'}m.style.display="flex"}catch(e){}};
(function(){function w(){var lp=document.getElementById("livePulse");if(lp&&!lp.__w){lp.__w=1;lp.style.cursor="pointer";lp.addEventListener("click",function(){window.__koeShowLivePulseDetail()})}}if(document.readyState==="loading")document.addEventListener("DOMContentLoaded",w);else w()})();
/* 設定パネル: 連続する項目をグループにまとめる(角丸カード1枚に行として並べる) */
function koeGroupSettings(){try{var panel=document.getElementById("mypageSettingsPanel");if(!panel||panel.__grouped)return;panel.__grouped=1;var kids=Array.prototype.slice.call(panel.children),run=[];function flush(){if(run.length){var g=document.createElement("div");g.className="settings-group";run[0].parentNode.insertBefore(g,run[0]);run.forEach(function(el){g.appendChild(el)});run=[]}}kids.forEach(function(el){if(el.tagName==="DETAILS"&&el.classList.contains("mypage-section")){run.push(el)}else{flush()}});flush()}catch(e){}}
if(document.readyState==="loading")document.addEventListener("DOMContentLoaded",koeGroupSettings);else koeGroupSettings();
/* 自分の投稿が規制対象(is_explicit)なら自動削除して通知する */
function koeAutoDelEnabled(){try{var v=localStorage.getItem("koe_auto_del_reg");return v===null?true:v==="1"}catch(e){return true}}
async function koeAutoDeleteRegulated(posts){if(!koeAutoDelEnabled())return;if(typeof myUserId==="undefined"||!myUserId||!Array.isArray(posts))return;var done={};try{done=JSON.parse(localStorage.getItem("koe_auto_del_done")||"{}")}catch(e){}var targets=posts.filter(function(p){return p&&p.id&&String(p.user_id)===String(myUserId)&&p.is_explicit&&!done[String(p.id)]});if(!targets.length)return;for(var i=0;i<targets.length;i++){var p=targets[i];done[String(p.id)]=1;try{var r=await callApi("delete_own_timeline_post",String(p.id),p.is_talk?1:0);var txt=(p.text||"").slice(0,40);if(r&&r.ok){toast("規制対象になった投稿を自動削除しました: "+txt,"error");try{window.KoeApp&&KoeApp.showNotification&&KoeApp.showNotification("規制対象の投稿を自動削除しました",txt||("投稿ID "+p.id))}catch(e){}}else{toast("規制対象の投稿を削除できませんでした (HTTP "+(r&&r.status||"?")+")","error")}}catch(e){}}try{localStorage.setItem("koe_auto_del_done",JSON.stringify(done))}catch(e){}if(targets.length){setTimeout(function(){try{loadTimeline(!1)}catch(e){}},800)}}
function initAutoDelReg(){try{var c=document.getElementById("autoDelRegChk");if(!c)return;c.checked=koeAutoDelEnabled();c.addEventListener("change",function(){try{localStorage.setItem("koe_auto_del_reg",c.checked?"1":"0")}catch(e){}})}catch(e){}}
function initBgNotify(){try{var c=document.getElementById("bgNotifyChk");if(!c)return;var v=localStorage.getItem("koe_bg_notify");var on=v===null?true:v==="1";c.checked=on;try{window.KoeApp&&KoeApp.setBackgroundNotify&&KoeApp.setBackgroundNotify(on)}catch(e){}c.addEventListener("change",function(){try{localStorage.setItem("koe_bg_notify",c.checked?"1":"0")}catch(e){}try{window.KoeApp&&KoeApp.setBackgroundNotify&&KoeApp.setBackgroundNotify(c.checked)}catch(e){}if(c.checked){try{window.KoeApp&&KoeApp.requestNotificationPermission&&KoeApp.requestNotificationPermission()}catch(e){}}})}catch(e){}}
window.__koeOpenPageFromNotif=function(p){try{if(typeof showPage==="function"){showPage(p||"notifications");if(typeof loadNotifications==="function"&&(p||"notifications")==="notifications")loadNotifications(typeof currentNotifKind!=="undefined"?currentNotifKind:"normal")}}catch(e){}};
function initNotifSound(){try{var ns=document.getElementById("notifSoundSelect");if(ns){ns.value=localStorage.getItem("koe_notify_sound")||"chime";ns.addEventListener("change",function(){try{localStorage.setItem("koe_notify_sound",ns.value)}catch(e){}if(ns.value!=="none"){try{sfx(ns.value)}catch(e){}}})}}catch(e){}}function initCallMini(){try{var s=document.getElementById("callMiniSelect");if(s){s.value=callMiniMode();s.addEventListener("change",function(){try{localStorage.setItem("koe_callmini",s.value)}catch(e){}if(window.AndroidApi&&window.AndroidApi.setPipEnabled){try{window.AndroidApi.setPipEnabled(s.value==="pip")}catch(e){}}})}if(window.AndroidApi&&window.AndroidApi.setPipEnabled){try{window.AndroidApi.setPipEnabled(callMiniMode()==="pip")}catch(e){}}}catch(e){}}function initPermUI(){try{var rb=document.getElementById("permRequestBtn"),sb=document.getElementById("permSettingsBtn");if(rb)rb.addEventListener("click",function(){requestAppPerms();setTimeout(checkAppPermissions,1200)});if(sb)sb.addEventListener("click",openPermSettings);checkAppPermissions();document.addEventListener("visibilitychange",function(){if(!document.hidden)checkAppPermissions()})}catch(e){}}function initSpeakIndicator(){try{initNotifSound()}catch(e){}try{initBgNotify()}catch(e){}try{initAutoDelReg()}catch(e){}try{initPinLock()}catch(e){}try{initCallMini()}catch(e){}try{initPermUI()}catch(e){}try{initPullToRefresh();setTimeout(function(){try{initPullToRefresh()}catch(e){}},600)}catch(e){}try{initTimelineFilters()}catch(e){}try{var vcc=document.getElementById("voiceContinuousChk");if(vcc){vcc.checked=localStorage.getItem("koe_voice_continuous")==="1";vcc.addEventListener("change",function(){try{localStorage.setItem("koe_voice_continuous",vcc.checked?"1":"0")}catch(e){}})}}catch(e){}try{var ci=document.getElementById("speakColorInput"),gs=document.getElementById("speakGlowSel"),pc=document.getElementById("speakPulseChk");if(ci){ci.value=localStorage.getItem("koe_speak_color")||"#2AC1C7";ci.addEventListener("input",function(){try{localStorage.setItem("koe_speak_color",ci.value)}catch(e){}applySpeakIndicator()})}if(gs){gs.value=localStorage.getItem("koe_speak_glow")||"normal";gs.addEventListener("change",function(){try{localStorage.setItem("koe_speak_glow",gs.value)}catch(e){}applySpeakIndicator()})}if(pc){pc.checked=localStorage.getItem("koe_speak_pulse")!=="0";pc.addEventListener("change",function(){try{localStorage.setItem("koe_speak_pulse",pc.checked?"1":"0")}catch(e){}applySpeakIndicator()})}}catch(e){}applySpeakIndicator()}function isAndroidApp(){return!!(window.AndroidApi&&window.AndroidApi.hasMicPermission)}function permState(){var mic=true,notif=true,cam=true;try{if(window.AndroidApi){if(window.AndroidApi.hasMicPermission)mic=window.AndroidApi.hasMicPermission();if(window.AndroidApi.hasNotifPermission)notif=window.AndroidApi.hasNotifPermission();if(window.AndroidApi.hasCameraPermission)cam=window.AndroidApi.hasCameraPermission()}}catch(e){}var ov=true;try{if(window.AndroidApi&&window.AndroidApi.hasOverlayPermission)ov=window.AndroidApi.hasOverlayPermission()}catch(e){}return{mic:mic,notif:notif,cam:cam,ov:ov}}function updatePermStatus(){var row=document.getElementById("permStatusRow");if(!row)return;if(!isAndroidApp()){row.textContent="この環境では権限管理は不要です(ブラウザ/PC)";return}var s=permState();function ln(label,ok){return(ok?" ":"⚠ ")+label+"："+(ok?"許可済み":"未許可")}row.innerHTML=ln("マイク(通話)",s.mic)+"<br>"+ln("通知",s.notif)+(s.notif?"":' <button class="theme-btn koe-notifgrant" style="width:auto;padding:2px 8px;font-size:12px;margin-left:6px;">許可する</button>')+"<br>"+ln("カメラ(写真撮影)",s.cam)+"<br>"+ln("他のアプリの上に表示(通話中)",s.ov)+(s.ov?"":' <button class="theme-btn koe-ovgrant" style="width:auto;padding:2px 8px;font-size:12px;margin-left:6px;">許可する</button>');var _ng=row.querySelector(".koe-notifgrant");if(_ng)_ng.addEventListener("click",function(){try{if(window.AndroidApi&&window.AndroidApi.requestNotificationPermission)window.AndroidApi.requestNotificationPermission()}catch(e){}setTimeout(function(){try{updatePermStatus()}catch(e){}},1500)});var _og=row.querySelector(".koe-ovgrant");if(_og)_og.addEventListener("click",function(){try{if(window.AndroidApi&&window.AndroidApi.requestOverlayPermission)window.AndroidApi.requestOverlayPermission()}catch(e){}setTimeout(function(){try{updatePermStatus()}catch(e){}},1500)})}function checkAppPermissions(){try{updatePermStatus();if(!isAndroidApp())return;var s=permState();var banner=document.getElementById("permBanner"),txt=document.getElementById("permBannerText");if(window.__permBannerDismissed){if(banner)banner.style.display="none";return}if(banner&&txt){if(!s.mic){txt.textContent=" 通話にはマイクの許可が必要です";banner.style.display="flex"}else if(!s.notif){txt.textContent=" 通知が届くように通知の許可をおすすめします";banner.style.display="flex"}else banner.style.display="none"}}catch(e){}}function requestAppPerms(){try{var st=(typeof permState==="function")?permState():null;if(st&&st.mic&&st.cam&&!st.notif&&window.AndroidApi&&window.AndroidApi.requestNotificationPermission){window.AndroidApi.requestNotificationPermission();return}if(window.AndroidApi&&window.AndroidApi.requestPermissions)window.AndroidApi.requestPermissions()}catch(e){}}function openPermSettings(){try{if(window.AndroidApi&&window.AndroidApi.openAppSettings)window.AndroidApi.openAppSettings()}catch(e){}}function dismissPermBanner(){window.__permBannerDismissed=true;var b=document.getElementById("permBanner");if(b)b.style.display="none"}window.__onPermResult=function(){checkAppPermissions()};async function ensureMicPermission(){try{if(!isAndroidApp())return true;if(window.AndroidApi.hasMicPermission())return true;requestAppPerms();await new Promise(function(res){var done=false;var prev=window.__onPermResult;window.__onPermResult=function(){try{prev&&prev()}catch(e){}if(!done){done=true;res()}};setTimeout(function(){if(!done){done=true;res()}},1e4)});return window.AndroidApi.hasMicPermission()}catch(e){return true}}function bioEnabled(){try{return localStorage.getItem("koe_bio")==="1"}catch(e){return false}}function tryBiometric(){try{if(window.AndroidApi&&window.AndroidApi.authBiometric){window.AndroidApi.authBiometric();return true}}catch(e){}return false}window.__onBiometricResult=function(ok,reason){if(ok){try{hidePinLock()}catch(e){}try{sfx("success")}catch(e){}}};function pinHash(s){var h=5381;for(var i=0;i<s.length;i++)h=(h<<5)+h+s.charCodeAt(i)>>>0;return"p"+h.toString(16)}function getPin(){try{return localStorage.getItem("koe_pin")||""}catch(e){return""}}function pinBuildPad(){var pad=document.getElementById("pinPad");if(!pad||pad.__built)return;pad.__built=true;["1","2","3","4","5","6","7","8","9","","0","del"].forEach(function(k){var b=document.createElement("button");b.className="pin-key";if(k==="")b.style.visibility="hidden";else if(k==="del"){b.textContent="⌫";b.onclick=function(){pinInput("del")}}else{b.textContent=k;b.onclick=function(){pinInput(k)}}pad.appendChild(b)})}function pinRenderDots(){var d=document.getElementById("pinDots");if(!d)return;d.innerHTML="";for(var i=0;i<4;i++){var s=document.createElement("span");if(i<(window.__pinBuf||"").length)s.className="on";d.appendChild(s)}}function showPinLock(mode){window.__pinMode=mode;window.__pinBuf="";window.__pinFirst="";pinBuildPad();var ov=document.getElementById("pinLockOverlay");if(ov)ov.style.display="flex";var t=document.getElementById("pinLockTitle");if(t)t.textContent=mode==="set"?"新しいPINを設定(4桁)":"PINを入力";var e=document.getElementById("pinError");if(e)e.textContent="";var bb=document.getElementById("bioUnlockBtn");if(bb){if(mode==="unlock"&&bioEnabled()){bb.style.display="inline-block";bb.onclick=function(){tryBiometric()};setTimeout(tryBiometric,350)}else bb.style.display="none"}pinRenderDots()}function hidePinLock(){var ov=document.getElementById("pinLockOverlay");if(ov)ov.style.display="none";window.__pinBuf=""}function pinInput(k){var buf=window.__pinBuf||"";if(k==="del"){window.__pinBuf=buf.slice(0,-1);pinRenderDots();return}if(buf.length>=4)return;buf+=k;window.__pinBuf=buf;pinRenderDots();try{sfx("select")}catch(e){}if(buf.length===4)setTimeout(pinProcess,120)}function pinProcess(){var buf=window.__pinBuf||"",mode=window.__pinMode,e=document.getElementById("pinError"),t=document.getElementById("pinLockTitle");if(mode==="set"){window.__pinFirst=buf;window.__pinMode="confirm";window.__pinBuf="";if(t)t.textContent="確認のためもう一度";pinRenderDots()}else if(mode==="confirm"){if(buf===window.__pinFirst){try{localStorage.setItem("koe_pin",pinHash(buf))}catch(err){}hidePinLock();try{toast(" PINロックを設定しました");sfx("success")}catch(err){}}else{window.__pinMode="set";window.__pinBuf="";window.__pinFirst="";if(e)e.textContent="PINが一致しません。最初からやり直してください";if(t)t.textContent="新しいPINを設定(4桁)";pinRenderDots();try{sfx("error")}catch(err){}}}else{if(pinHash(buf)===getPin()){hidePinLock();try{sfx("success")}catch(err){}}else{window.__pinBuf="";if(e)e.textContent="PINが違います";pinRenderDots();try{sfx("error")}catch(err){}}}}function initPinLock(){try{var chk=document.getElementById("pinLockChk");if(chk){chk.checked=!!getPin();chk.addEventListener("change",function(){if(chk.checked)showPinLock("set");else{try{localStorage.removeItem("koe_pin");localStorage.removeItem("koe_bio")}catch(e){}var bc=document.getElementById("bioChk");if(bc)bc.checked=false;try{toast("PINロックを解除しました")}catch(e){}}})}var bchk=document.getElementById("bioChk");if(bchk){bchk.checked=bioEnabled();bchk.addEventListener("change",function(){if(bchk.checked){if(!getPin()){bchk.checked=false;try{toast("先にPINロックを設定してください")}catch(e){}return}var ok=true;try{if(window.AndroidApi&&window.AndroidApi.biometricAvailable)ok=window.AndroidApi.biometricAvailable()}catch(e){}if(!ok){bchk.checked=false;try{toast("この端末は生体認証が使えません(未登録・非Android)")}catch(e){}return}try{localStorage.setItem("koe_bio","1")}catch(e){}try{toast(" 生体認証を有効にしました")}catch(e){}}else{try{localStorage.removeItem("koe_bio")}catch(e){}}})}}catch(e){}}function applyNameMarquee(){try{document.querySelectorAll(".callv2-pname").forEach(function(d){var sp=d.firstElementChild;if(!sp)return;var over=sp.scrollWidth-d.clientWidth;if(over>4){d.style.setProperty("--md",-over-6+"px");d.classList.add("marquee")}else{d.classList.remove("marquee");d.style.removeProperty("--md")}})}catch(e){}}/* ==== 発言権に応じたマイク配信の制御 ====
   これまでは入室時に必ずマイクを publish していたため、聞き専でも声が届いてしまい、
   さらに「音声配信があるか」で役割を判定していたので聞き専が発言者に見えていた。
   サーバーの名簿(speakers)に自分が入っている時だけ publish し、外れたら unpublish する。 */
function koeCanSpeak(){try{if(skIsOwner)return true;var my=window.__myUserId||0;var r=window.__roomRoster;if(!r)return false;if(r.owner&&Number(r.owner)===my)return true;return (r.speakers||[]).some(function(x){return Number(x.user_id||x.userId)===my})}catch(e){return false}}
window.__koeSyncingPub=false;
async function koeSyncPublish(initial){if(!skMe||!skLocalStream)return;if(window.__koeSyncingPub)return;window.__koeSyncingPub=true;try{var can=koeCanSpeak();var mb=document.getElementById("callMuteBtn");if(can&&!skMyPub){skMyPub=await skMe.publish(skLocalStream);try{if(skMuted)skMyPub.disable()}catch(e){}try{skLocalStream.track&&(skLocalStream.track.enabled=!skMuted)}catch(e){}if(mb){mb.disabled=false;mb.title="";var sp=mb.querySelector("span");if(sp)sp.textContent=skMuted?"ミュート解除":"ミュート"}if(!initial){try{toast("🎤 発言できるようになりました");sfx("unmute")}catch(e){}}}else if(!can&&skMyPub){try{await skMe.unpublish(skMyPub.id)}catch(e){}skMyPub=null;try{skLocalStream.track&&(skLocalStream.track.enabled=false)}catch(e){}if(mb){mb.disabled=true;mb.title="聞き専のため発言できません";var sp2=mb.querySelector("span");if(sp2)sp2.textContent="聞き専"}if(!initial){try{toast("🔇 聞き専になりました")}catch(e){}}}else if(!can&&!skMyPub){try{skLocalStream.track&&(skLocalStream.track.enabled=false)}catch(e){}if(mb){mb.disabled=true;mb.title="聞き専のため発言できません";var sp3=mb.querySelector("span");if(sp3)sp3.textContent="聞き専"}}}catch(e){try{callLog("publish同期エラー: "+e)}catch(_){}}finally{window.__koeSyncingPub=false}try{updateCallGrid()}catch(e){}}
/* ==== 相手ごとの音量 ==== */
window.__koeRemoteAudio=window.__koeRemoteAudio||{};
window.__koeGains=window.__koeGains||{};
function koeGetVol(uid){try{var v=localStorage.getItem("koe_vol_"+uid);return v===null?1:Math.max(0,Math.min(2,parseFloat(v)))}catch(e){return 1}}
/* 受信音声を GainNode 経由で鳴らす(0〜200%)。AudioContext が使えない環境は audio.volume(0〜100%)にフォールバック */
function koeAttachGain(uid,a,ms){try{audioCtx||(audioCtx=new(window.AudioContext||window.webkitAudioContext));if(audioCtx.state==="suspended"){try{audioCtx.resume()}catch(e){}}var src=audioCtx.createMediaStreamSource(ms),g=audioCtx.createGain();g.gain.value=koeGetVol(uid);src.connect(g);g.connect(audioCtx.destination);(window.__koeGains[uid]=window.__koeGains[uid]||[]).push(g);a.muted=true;a.__koeGain=g;return true}catch(e){try{a.volume=Math.min(1,koeGetVol(uid))}catch(_){}return false}}
function koeSetVol(uid,v){try{localStorage.setItem("koe_vol_"+uid,String(v))}catch(e){}try{(window.__koeGains[uid]||[]).forEach(function(g){g.gain.value=v})}catch(e){}try{(window.__koeRemoteAudio[uid]||[]).forEach(function(a){if(!a.__koeGain)a.volume=Math.min(1,v)})}catch(e){}}
function koeShowVolume(uid,name){try{var old=document.getElementById("koeVolPop");if(old)old.remove();var oldb=document.getElementById("koeVolBackdrop");if(oldb)oldb.remove();var bdp=document.createElement("div");bdp.id="koeVolBackdrop";bdp.className="koe-vol-backdrop";document.body.appendChild(bdp);var pop=document.createElement("div");pop.id="koeVolPop";pop.className="koe-vol-pop";var v=Math.round(koeGetVol(uid)*100);pop.innerHTML='<div class="koe-vol-head"><span>'+escapeHtml(name||("user "+uid))+' の音量</span><button class="modal-close" id="koeVolClose">✕</button></div><div class="koe-vol-row"><span>🔈</span><input type="range" id="koeVolRange" min="0" max="200" value="'+v+'"><span>🔊</span></div><div class="koe-vol-val" id="koeVolVal">'+v+'%</div><div class="koe-vol-btns"><button class="btn-secondary" data-v="0">ミュート</button><button class="btn-secondary" data-v="50">50%</button><button class="btn-secondary" data-v="100">100%</button><button class="btn-secondary" data-v="150">150%</button></div>';document.body.appendChild(pop);var rng=pop.querySelector("#koeVolRange"),val=pop.querySelector("#koeVolVal");function apply(x){x=Math.max(0,Math.min(200,parseInt(x,10)||0));rng.value=x;val.textContent=x+"%";koeSetVol(uid,x/100)}rng.addEventListener("input",function(){apply(rng.value)});pop.querySelectorAll(".koe-vol-btns button").forEach(function(b){b.addEventListener("click",function(){apply(b.dataset.v)})});function closeVol(){try{pop.remove()}catch(e){}try{bdp.remove()}catch(e){}}pop.querySelector("#koeVolClose").addEventListener("click",closeVol);bdp.addEventListener("click",closeVol)}catch(e){}}
function renderCallParticipants(participants){const el=document.getElementById("callParticipantList");if(!el)return;try{var __sig=(participants||[]).map(function(p){return p.user_id+":"+p.role+":"+(p.is_mute?1:0)+":"+(p.is_owner?1:0)+":"+(p.just_changed?1:0)}).join("|");if(el.__partSig===__sig){return;} el.__partSig=__sig;}catch(e){}setTimeout(applyNameMarquee,60);if(!participants||!participants.length)return el.classList.remove("grouped","size-lg","size-md","size-sm","size-xs"),void(el.innerHTML='<div class="callv2-empty">参加者を待っています…</div>');const card=p=>{const nm=p.name||"user "+p.user_id,initial=(nm||"?").charAt(0).toUpperCase(),bg=p.icon_url?`background-image:url('${escAttr(p.icon_url)}')`:`background-color:${callAvatarColor(p.user_id)}`;return`<div class="callv2-pcard">\n      <div class="callv2-pav ${("applicant"===p.role?"raised":"listener"===p.role?"listener":"")+(p.just_changed?" just-changed":"")}" data-uid="${p.user_id}" style="${bg}">\n        ${p.icon_url?"":escapeHtml(initial)}\n        ${p.is_owner?'<span class="callv2-crown"><svg viewBox="0 0 24 24" fill="currentColor"><path d="M3 8l4.5 3.5L12 5l4.5 6.5L21 8l-1.5 10.5a1 1 0 0 1-1 .5H5.5a1 1 0 0 1-1-.5L3 8Z"/></svg></span>':""}\n        ${p.is_mute&&"listener"!==p.role?'<span class="callv2-pmute"><svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="#ccc" stroke-width="2"><rect x="9" y="3" width="6" height="9" rx="3" fill="#ccc" stroke="none"/><path d="M6 11a6 6 0 0 0 12 0" stroke-linecap="round"/><path d="M4 3l16 16" stroke-linecap="round"/></svg></span>':""}\n        ${"applicant"===p.role?'<span class="callv2-phand"><svg viewBox="0 0 24 24" width="13" height="13" fill="#fff"><path d="M7 11V6a1.4 1.4 0 0 1 2.8 0v4m0 0V4.4a1.4 1.4 0 0 1 2.8 0V10m0 0V6a1.4 1.4 0 0 1 2.8 0v6m0-2a1.4 1.4 0 0 1 2.8 0v4a6 6 0 0 1-6 6h-1a6 6 0 0 1-5.4-3.4L4.6 13a1.5 1.5 0 0 1 2.6-1.4"/></svg></span>':""}\n        ${("speaker"===p.role&&Number(p.user_id)!==Number(window.__myUserId||0))?`<button class="callv2-vol" data-uid="${p.user_id}" data-name="${escAttr(nm)}" title="音量" onclick="event.stopPropagation();koeShowVolume(${p.user_id},this.dataset.name)"><svg viewBox="0 0 24 24" width="13" height="13" fill="currentColor"><path d="M4 9v6h4l5 4V5L8 9H4Z"/><path d="M16 8.5a5 5 0 0 1 0 7M18.5 6a8.5 8.5 0 0 1 0 12" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg></button>`:""}\n      </div>\n      <div class="callv2-pname"><span>${escapeHtml(nm)}</span></div>\n    </div>`},total=participants.length;el.classList.remove("size-lg","size-md","size-sm","size-xs"),el.classList.add(total<=3?"size-lg":total<=6?"size-md":total<=12?"size-sm":"size-xs");const speakers=participants.filter(p=>"speaker"===p.role),applicants=participants.filter(p=>"applicant"===p.role),listeners=participants.filter(p=>"listener"===p.role);if(!applicants.length&&!listeners.length)return el.classList.remove("grouped"),void(el.innerHTML=speakers.map(card).join(""));el.classList.add("grouped");let html="";const section=(icon,label,n)=>`<div class="callv2-section">${icon}<span>${label}</span><b>${n}</b></div>`;speakers.length&&(html+=section('<svg viewBox="0 0 24 24" width="13" height="13" fill="currentColor"><rect x="9" y="3" width="6" height="10" rx="3"/><path d="M6 11a6 6 0 0 0 12 0" fill="none" stroke="currentColor" stroke-width="2"/></svg>',"発言中",speakers.length)+`<div class="callv2-subgrid">${speakers.map(card).join("")}</div>`),applicants.length&&(html+=section('<span style="color:#F5C542"></span>',"挙手中",applicants.length)+`<div class="callv2-subgrid">${applicants.map(card).join("")}</div>`),listeners.length&&(html+=section('<svg viewBox="0 0 24 24" width="13" height="13" fill="currentColor"><path d="M4 14v3a2 2 0 0 0 2 2h1v-6H6a2 2 0 0 0-2 1zM17 13v6h1a2 2 0 0 0 2-2v-3a2 2 0 0 0-2-1zM12 3a9 9 0 0 0-9 9v1a3 3 0 0 1 3-1V12a6 6 0 0 1 12 0v1a3 3 0 0 1 3 1v-1a9 9 0 0 0-9-9z"/></svg>',"聞いてるだけ",listeners.length)+`<div class="callv2-subgrid">${listeners.map(card).join("")}</div>`),el.innerHTML=html}function callMemberUid(m){try{var nm=String(m&&(m.name||m.id)||"");var u=parseInt(nm.split("_")[0],10);if(!u){var mm=nm.match(/\d{3,}/);u=mm?parseInt(mm[0],10):0}return u||0}catch(e){return 0}}function callMemberName(m){var uid=callMemberUid(m);if(!uid)return"";try{var r=window.__roomRoster;if(r){var all=(r.speakers||[]).concat(r.listeners||[],r.applicants||[]);var x=all.find(function(y){return Number(y.user_id)===uid});if(x&&x.name)return x.name}}catch(e){}try{var nm=String(m&&m.name||"");var parts=nm.split("_");if(parts.length>1&&parts.slice(1).join("_"))return parts.slice(1).join("_")}catch(e){}return"ユーザー"+uid}function addSpeakAnalyser(uid,mediaStream){try{if(!uid||!mediaStream)return;audioCtx||(audioCtx=new(window.AudioContext||window.webkitAudioContext));const src=audioCtx.createMediaStreamSource(mediaStream),an=audioCtx.createAnalyser();an.fftSize=512,src.connect(an),callAnalysers.push({uid:uid,analyser:an,buf:new Uint8Array(an.fftSize)}),callSpeakLoop||startSpeakLoop()}catch(e){}}function callMiniMode(){try{return localStorage.getItem("koe_callmini")||"bubble"}catch(e){return"bubble"}}function findRosterUser(uid){try{var r=window.__roomRoster;if(!r)return null;var all=(r.speakers||[]).concat(r.listeners||[],r.applicants||[]);return all.find(function(x){return Number(x.user_id)===Number(uid)})||null}catch(e){return null}}function updateSpeakerBubble(uid){var bub=document.getElementById("callSpeakerBubble");if(!bub)return;var talking=!!uid;bub.classList.toggle("talking",talking);if(!uid)return;if(uid===bub.__uid)return;bub.__uid=uid;var info=findRosterUser(uid)||{};window.__lastSpeakerName=info.name||"user "+uid;if(window.__overlayActive&&window.AndroidApi&&window.AndroidApi.updateOverlay){try{window.AndroidApi.updateOverlay(window.__lastSpeakerName)}catch(e){}}var av=document.getElementById("csbAv"),lb=document.getElementById("csbLabel");if(av){if(info.icon_url){av.style.backgroundImage="url('"+info.icon_url+"')";av.textContent=""}else{av.style.backgroundImage="";try{av.style.backgroundColor=callAvatarColor(uid)}catch(e){}av.textContent=((info.name||"?").charAt(0)||"?").toUpperCase()}}if(lb)lb.textContent=info.name||"user "+uid}function showCallMini(){var mode=callMiniMode();var banner=document.getElementById("returnToCallBanner"),bub=document.getElementById("callSpeakerBubble");if(banner)banner.style.display=mode==="banner"?"flex":"none";if(bub)bub.style.display=mode==="bubble"?"flex":"none";window.__overlayActive=false;var pcb=document.getElementById("pageCallReturnBanner");if(pcb)pcb.style.display="flex";if(mode==="pip"){try{if(window.AndroidApi&&window.AndroidApi.enterPip)window.AndroidApi.enterPip()}catch(e){}}else if(mode==="overlay"){try{if(window.AndroidApi&&window.AndroidApi.hasOverlayPermission){if(!window.AndroidApi.hasOverlayPermission()){try{window.AndroidApi.requestOverlayPermission()}catch(e){}toast("「他のアプリの上に表示」の許可が必要です。設定で許可してください")}else{window.__overlayActive=true;try{window.AndroidApi.showOverlay(window.__lastSpeakerName||"通話中")}catch(e){}}}else{toast("この機能はアプリ版でのみ使えます")}}catch(e){}}}function hideCallMini(){var banner=document.getElementById("returnToCallBanner"),bub=document.getElementById("callSpeakerBubble");if(banner)banner.style.display="none";if(bub)bub.style.display="none";window.__overlayActive=false;var pcb=document.getElementById("pageCallReturnBanner");if(pcb)pcb.style.display="none";try{if(window.AndroidApi&&window.AndroidApi.hideOverlay)window.AndroidApi.hideOverlay()}catch(e){}}function initSpeakerBubbleDrag(){var bub=document.getElementById("callSpeakerBubble");if(!bub||bub.__dragInit)return;bub.__dragInit=true;var sx=0,sy=0,ox=0,oy=0,moved=false,dragging=false;function down(e){dragging=true;moved=false;var t=e.touches?e.touches[0]:e;sx=t.clientX;sy=t.clientY;var r=bub.getBoundingClientRect();ox=r.left;oy=r.top}function move(e){if(!dragging)return;var t=e.touches?e.touches[0]:e;var dx=t.clientX-sx,dy=t.clientY-sy;if(Math.abs(dx)+Math.abs(dy)>6)moved=true;bub.style.left=ox+dx+"px";bub.style.top=oy+dy+"px";bub.style.right="auto";bub.style.bottom="auto";if(e.cancelable)e.preventDefault()}function up(){if(!dragging)return;dragging=false;if(!moved){try{reopenCall()}catch(e){}}}bub.addEventListener("touchstart",down,{passive:true});bub.addEventListener("touchmove",move,{passive:false});bub.addEventListener("touchend",up);bub.addEventListener("mousedown",down);document.addEventListener("mousemove",move);document.addEventListener("mouseup",up)}function loadCallLogs(){var box=document.getElementById("callLogList");if(!box)return;var logs=[];try{logs=JSON.parse(localStorage.getItem("koe_call_logs")||"[]")}catch(e){}window.__callLogs=logs;if(!logs.length){box.innerHTML='<div class="empty-msg">通話ログはまだありません。グループ通話に参加すると自動で記録されます。</div>';return}box.innerHTML=logs.map(function(r,i){var top=(r.participants||[]).filter(function(p){return p.uid===r.topUid&&p.speakMs>0})[0];var d=new Date(r.start);var dstr=d.getMonth()+1+"/"+d.getDate()+" "+d.getHours()+":"+(d.getMinutes()<10?"0":"")+d.getMinutes();return'<div class="card" onclick="showCallLogDetail('+i+')">'+'<div class="card-body"><div class="card-name">'+escapeHtml(r.room||"通話")+"</div>"+'<div class="card-sub">'+dstr+" ・ "+csFmtDur(r.dur)+" ・ "+(r.participants||[]).length+"人</div>"+(top?'<div class="card-sub"> 最多発言: '+escapeHtml(top.name)+" ("+csFmtDur(top.speakMs)+")</div>":'<div class="card-sub" style="opacity:.6;">発言記録なし</div>')+"</div></div>"}).join("")}function showCallLogDetail(i){var r=(window.__callLogs||[])[i];if(!r)return;var evLabel={raise:" 手を挙げた",promote:" 発言者になった",lower:" 手を下げた",join:" 入室した",leave:" 退出した",mute:" ミュートした",unmute:" ミュート解除した"};var parts=(r.participants||[]).map(function(p){var badges=(p.isOwner?'<span class="clog-badge owner"> オーナー</span>':"")+(p.isListener?'<span class="clog-badge listener"> 聞き専</span>':p.speakMs>0?'<span class="clog-badge speaker"> 発言</span>':"");var isTop=p.uid===r.topUid&&p.speakMs>0;return'<div class="clog-part'+(isTop?" top":"")+'">'+avatarHtml(p.name,p.icon)+'<div style="flex:1;min-width:0;"><div class="card-name">'+(isTop?" ":"")+escapeHtml(p.name)+' <span class="uid-tag">ID:'+p.uid+"</span></div>"+'<div class="card-sub">発言 '+csFmtDur(p.speakMs)+" ・ 滞在 "+csFmtDur(p.stayMs)+"</div>"+"<div>"+(badges||'<span class="clog-badge listener">参加</span>')+"</div></div></div>"}).join("");var events=(r.events||[]).map(function(e){return'<div class="clog-ev"><span class="clog-ev-t">'+csFmtDur(e.t)+"</span> "+(evLabel[e.type]||e.type)+" — "+escapeHtml(e.name)+"</div>"}).join("")||'<div class="empty-msg">記録されたイベントはありません</div>';var chat=(r.chat||[]).map(function(c){return'<div class="clog-chat"><span class="clog-ev-t">'+csFmtDur(c.t)+"</span> <b>"+escapeHtml(c.name)+"</b>: "+escapeHtml(c.text)+"</div>"}).join("")||'<div class="empty-msg">チャットはありませんでした</div>';var body=document.getElementById("callLogModalBody");if(body)body.innerHTML='<div class="card-name" style="font-size:16px;">'+escapeHtml(r.room||"通話")+"</div>"+'<div class="card-sub">'+new Date(r.start).toLocaleString()+" ・ 通話時間 "+csFmtDur(r.dur)+"</div>"+'<div class="clog-sec">参加者 ('+(r.participants||[]).length+"人) ／ 発言時間順</div>"+parts+'<div class="clog-sec">ログ(誰が上がった・手を下げた・入退室)</div>'+events+'<div class="clog-sec"> チャット ('+(r.chat||[]).length+"件)</div>"+chat;var m=document.getElementById("callLogModal");if(m)m.style.display="flex";try{sfx("open")}catch(e){}}function closeCallLogModal(){var m=document.getElementById("callLogModal");if(m)m.style.display="none"}function csInit(roomName,ownerUid){window.__callSession={startMs:Date.now(),roomName:roomName||"通話",ownerUid:Number(ownerUid)||0,events:[],speakMs:{},seen:{},chat:[],__chatSeen:{}}}function csSeen(uid,name,icon){if(!window.__callSession||!uid)return;var s=window.__callSession;if(!s.seen[uid])s.seen[uid]={uid:uid,name:name||"user "+uid,icon:icon||"",joinMs:Date.now()};else{if(name&&String(s.seen[uid].name).indexOf("user ")===0)s.seen[uid].name=name;if(icon&&!s.seen[uid].icon)s.seen[uid].icon=icon}}function csEvent(type,uid,name){if(!window.__callSession||!uid)return;var s=window.__callSession;s.events.push({t:Date.now()-s.startMs,type:type,uid:Number(uid),name:name||s.seen[uid]&&s.seen[uid].name||"user "+uid})}function csSpeakTick(uid,ms){if(!window.__callSession||!uid)return;window.__callSession.speakMs[uid]=(window.__callSession.speakMs[uid]||0)+ms}function csSave(){try{var s=window.__callSession;if(!s){return}var durMs=Date.now()-s.startMs;if(durMs<3e3){window.__callSession=null;return}var topUid=0,topMs=0;Object.keys(s.speakMs).forEach(function(u){if(s.speakMs[u]>topMs){topMs=s.speakMs[u];topUid=Number(u)}});var r=window.__roomRoster||{};var speakerUids=(r.speakers||[]).map(function(u){return Number(u.user_id)});var listenerUids=(r.listeners||[]).map(function(u){return Number(u.user_id)});var participants=Object.keys(s.seen).map(function(u){var uid=Number(u),p=s.seen[u];return{uid:uid,name:p.name,icon:p.icon,speakMs:s.speakMs[uid]||0,isOwner:uid===s.ownerUid,isListener:listenerUids.indexOf(uid)>=0&&speakerUids.indexOf(uid)<0,stayMs:Date.now()-p.joinMs}});participants.sort(function(a,b){return b.speakMs-a.speakMs});var rec={start:s.startMs,dur:durMs,room:s.roomName,ownerUid:s.ownerUid,topUid:topUid,topMs:topMs,participants:participants,events:s.events.slice(-150),chat:(s.chat||[]).slice(-200)};var logs=[];try{logs=JSON.parse(localStorage.getItem("koe_call_logs")||"[]")}catch(e){}logs.unshift(rec);logs=logs.slice(0,20);try{localStorage.setItem("koe_call_logs",JSON.stringify(logs))}catch(e){}}catch(e){}finally{window.__callSession=null}}function csFmtDur(ms){var s=Math.round(ms/1e3);var m=Math.floor(s/60);s=s%60;return m+":"+(s<10?"0":"")+s}function startSpeakLoop(){callSpeakLoop=setInterval(()=>{var __maxLvl=0,__maxUid=0;callAnalysers.forEach(a=>{let lvl=0;try{a.analyser.getByteTimeDomainData(a.buf);let sum=0;for(let i=0;i<a.buf.length;i++){const v=(a.buf[i]-128)/128;sum+=v*v}lvl=Math.sqrt(sum/a.buf.length)}catch(e){}const rawSpeaking=lvl>function(){try{return.02+.006*(10-parseInt(localStorage.getItem("koe_speaksens")||"4",10))}catch(e){return.045}}()&&!(a.uid===window.__myUserId&&skMuted);var __now=Date.now();if(!window.__lastSpoke)window.__lastSpoke={};if(rawSpeaking)window.__lastSpoke[a.uid]=__now;const speaking=!!(window.__lastSpoke[a.uid]&&__now-window.__lastSpoke[a.uid]<500)&&!(a.uid===window.__myUserId&&skMuted),el=document.querySelector(`.callv2-pav[data-uid="${a.uid}"]`);if(el&&el.classList.toggle("speaking",speaking),a.uid===window.__myUserId){const f=document.getElementById("callMicMeterFill");f&&(f.style.width=Math.min(100,Math.round(450*lvl))+"%")}if(rawSpeaking){try{csSpeakTick(a.uid,140)}catch(e){}if(lvl>__maxLvl){__maxLvl=lvl;__maxUid=a.uid}}});try{updateSpeakerBubble(__maxUid)}catch(e){}},140)}function stopSpeakAnalysers(){callSpeakLoop&&(clearInterval(callSpeakLoop),callSpeakLoop=null),callAnalysers=[];try{audioCtx&&(audioCtx.close(),audioCtx=null)}catch(e){}}async function updateCallGrid(){try{return await __updateCallGridInner()}catch(e){try{callLog("グリッド描画エラー: "+e)}catch(ee){}}}async function __updateCallGridInner(){const myUid=window.__myUserId||0,roster=window.__roomRoster,muteByUid={},skUids=[];skRoom&&(skRoom.members||[]).forEach(m=>{const nm=String(m.name||m.id||"");let uid=parseInt(nm.split("_")[0],10);if(!uid){const mm=nm.match(/\d{3,}/);mm&&(uid=parseInt(mm[0],10))}if(uid){-1===skUids.indexOf(uid)&&skUids.push(uid);try{const audio=(m.publications||[]).filter(pp=>"audio"===pp.contentType);audio.length&&(muteByUid[uid]=audio.every(pp=>"disabled"===pp.state))}catch(e){}}});const ownerUid=window.__callOwnerUid||roster&&roster.owner||0;if(roster&&(roster.speakers.length||roster.listeners.length||roster.applicants.length)){const seen=new Set,mk=(u,role)=>{const uid=Number(u.user_id);if(!uid||seen.has(uid))return null;seen.add(uid);let muted=!!muteByUid[uid];uid===myUid&&(muted=skMuted);const justChanged=window.__justRaisedUids&&window.__justRaisedUids.has(uid)||window.__justPromotedUids&&window.__justPromotedUids.has(uid);return{user_id:uid,name:u.name||"user "+uid,icon_url:u.icon_url||"",is_owner:uid===ownerUid,is_mute:muted,role:role,just_changed:justChanged}},parts=[];if(ownerUid){const ou=roster.speakers.concat(roster.listeners).find(x=>Number(x.user_id)===ownerUid)||{user_id:ownerUid,name:myUid===ownerUid?"あなた(主催)":"主催者"},p=mk(ou,"speaker");p&&parts.push(p)}return roster.speakers.forEach(u=>{const p=mk(u,"speaker");p&&parts.push(p)}),roster.applicants.forEach(u=>{const p=mk(u,"applicant");p&&parts.push(p)}),roster.listeners.forEach(u=>{const p=mk(u,"listener");p&&parts.push(p)}),void renderCallParticipants(parts)}if(!skRoom||!skUids.length)return void renderCallParticipants([]);const resolved={};try{const r=await callApi("resolve_users",skUids.join(","));r&&r.ok&&(r.users||[]).forEach(u=>{resolved[u.user_id]=u})}catch(e){}renderCallParticipants(skUids.map(uid=>{const u=resolved[uid]||{};let muted=!!muteByUid[uid];uid===myUid&&(muted=skMuted);const raised=!(!window.__handRaisedUids||!window.__handRaisedUids.has(uid));const hasAudio=Object.prototype.hasOwnProperty.call(muteByUid,uid)||uid===myUid;const role=raised?"applicant":(hasAudio?"speaker":"listener");return{user_id:uid,name:u.name||"user "+uid,icon_url:u.icon_url||"",is_owner:uid===ownerUid,is_mute:muted,role:role}}))}let callTimerInterval=null,callStartMs=null;function startCallTimer(){stopCallTimer(),callStartMs=Date.now();const tick=()=>{const el=document.getElementById("callTimer");if(!el)return;const s=Math.floor((Date.now()-callStartMs)/1e3),mm=String(Math.floor(s/60)).padStart(2,"0"),ss=String(s%60).padStart(2,"0");el.textContent=`${mm}:${ss}`};tick(),callTimerInterval=setInterval(tick,1e3)}function stopCallTimer(){callTimerInterval&&(clearInterval(callTimerInterval),callTimerInterval=null),callStartMs=null}async function populateCallDevices(){try{if(!navigator.mediaDevices||!navigator.mediaDevices.enumerateDevices)return void callLog("navigator.mediaDevices が使えません(セキュアコンテキスト外の可能性)");const devices=await navigator.mediaDevices.enumerateDevices(),mic=document.getElementById("callMicSelect"),spk=document.getElementById("callSpeakerSelect");mic.innerHTML="",spk.innerHTML="";const savedMic=localStorage.getItem(CALL_LS_MIC),savedSpk=localStorage.getItem(CALL_LS_SPK);devices.filter(d=>"audioinput"===d.kind).forEach(d=>{const o=document.createElement("option");o.value=d.deviceId,o.textContent=d.label||`マイク ${mic.length+1}`,d.deviceId===savedMic&&(o.selected=!0),mic.appendChild(o)}),devices.filter(d=>"audiooutput"===d.kind).forEach(d=>{const o=document.createElement("option");o.value=d.deviceId,o.textContent=d.label||`スピーカー ${spk.length+1}`,d.deviceId===savedSpk&&(o.selected=!0),spk.appendChild(o)}),mic.onchange=()=>localStorage.setItem(CALL_LS_MIC,mic.value),spk.onchange=()=>{localStorage.setItem(CALL_LS_SPK,spk.value),document.querySelectorAll("#remoteAudios audio").forEach(a=>{a.setSinkId&&a.setSinkId(spk.value).catch(()=>{})})}}catch(e){callLog("デバイス一覧取得失敗: "+e)}}async function startInWindowCall(call){if(typeof skyway_room==="undefined"){try{await loadScript("vendor/skyway_room.js")}catch(e){}}if(!call)return;if(skCurrentRoomId){try{await callApi("room_leave",skCurrentRoomId)}catch(e){}}await teardownCall(!1),skCurrentRoomId=call.room_id||null,skIsOwner=!!call.is_owner,window.__callOwnerUid=call.owner_user_id||0;try{window.__myUserId=parseInt(String(call.member||"").split("_")[0],10)||0}catch(e){window.__myUserId=0}document.getElementById("callOverlay").style.display="flex";try{window.AndroidApi&&window.AndroidApi.startCallAudio&&window.AndroidApi.startCallAudio()}catch(e){}try{const cmEl=document.getElementById("callMascot");if(cmEl)cmEl.style.display="flex";}catch(e){}const _rcb=document.getElementById("returnToCallBanner");_rcb&&(_rcb.style.display="none"),document.getElementById("callLog").textContent="",document.getElementById("callRoomName").textContent=call.title||call.channel||"-",document.getElementById("callMemberCount").textContent="0",document.getElementById("callLeaveBtn").disabled=!0;{const mb=document.getElementById("callMuteBtn");mb.disabled=!1,mb.classList.remove("muted");const ms=mb.querySelector("span");ms&&(ms.textContent="マイク")}if(document.getElementById("callOwnerControls").style.display=skIsOwner?"block":"none",document.getElementById("callCloseRoomBtn").style.display=skIsOwner?"block":"none",renderCallParticipants(call.participants||[]),await populateCallDevices(),"undefined"!=typeof skyway_room)try{callSetStatus("SkyWayContext.Create 実行中...");const{SkyWayContext:SkyWayContext,SkyWayRoom:SkyWayRoom,SkyWayStreamFactory:SkyWayStreamFactory}=skyway_room,context=await SkyWayContext.Create(call.auth_token);callSetStatus("マイク取得中...(許可を求められたら許可してください)");const savedMic=localStorage.getItem(CALL_LS_MIC);skLocalStream=await SkyWayStreamFactory.createMicrophoneAudioStream(savedMic?{deviceId:{exact:savedMic}}:void 0),callSetStatus("接続中…"),skRoom=await SkyWayRoom.FindOrCreate(context,{type:"p2p",name:call.channel}),skMe=await skRoom.join({name:call.member||"me"}),document.getElementById("callMemberCount").textContent=skRoom.members.length,updateCallGrid();
const skSubscribedPubIds=new Set();
window.__skSubs=window.__skSubs||{};
/* 公式と同じく「名簿(speakers)に入っている人」だけを購読する。聞き専(listeners)や名簿外の人が
   音声を配信していても再生しない。名簿がまだ無い時は主催者のみ許可。 */
const koeAllowedSpeaker=uid=>{try{if(!uid)return false;var r=window.__roomRoster;var owner=window.__callOwnerUid||(r&&r.owner)||0;if(uid===owner)return true;if(!r)return false;return (r.speakers||[]).some(x=>Number(x.user_id||x.userId)===uid)}catch(e){return false}};
const skSubscribeToPublication=pub=>{
  try{if(!pub||pub.publisher.id===skMe.id)return;if(skSubscribedPubIds.has(pub.id))return;}catch(err){return;}
  const pubUid=function(){try{return parseInt(String(pub.publisher.name||"").split("_")[0],10)||0}catch(_){return 0}}();
  if(!koeAllowedSpeaker(pubUid)){try{callLog("購読保留(聞き専/名簿外): user "+pubUid)}catch(_){}return;}
  skSubscribedPubIds.add(pub.id);
  try{if("audio"===pub.contentType){pub.onEnabled.add(updateCallGrid);pub.onDisabled.add(updateCallGrid);}}catch(err){}
  skMe.subscribe(pub.id).then(({subscription:subscription,stream:stream})=>{const ms=stream.track?new MediaStream([stream.track]):stream,a=document.createElement("audio");a.autoplay=!0,a.srcObject=ms;try{const _p=a.play();_p&&_p.catch&&_p.catch(()=>{setTimeout(()=>{try{a.play().catch(()=>{})}catch(_){}},300)})}catch(_){}try{pubUid&&addSpeakAnalyser(pubUid,ms)}catch(_){}const savedSpk=localStorage.getItem(CALL_LS_SPK);savedSpk&&a.setSinkId&&a.setSinkId(savedSpk).catch(()=>{});try{if(pubUid){a.dataset.uid=String(pubUid);(window.__koeRemoteAudio[pubUid]=window.__koeRemoteAudio[pubUid]||[]).push(a);koeAttachGain(pubUid,a,ms)}}catch(_){}try{window.__skSubs[pub.id]={sub:subscription,audio:a,uid:pubUid}}catch(_){}document.getElementById("remoteAudios").appendChild(a);callSetStatus("接続中")}).catch(()=>{skSubscribedPubIds.delete(pub.id)});
};
/* 名簿が更新されたら購読を同期: 発言者になった人を購読、聞き専に戻った人は購読解除 */
window.__koeSyncSubs=async function(){try{if(!skRoom||!skMe)return;(skRoom.publications||[]).forEach(skSubscribeToPublication);var ids=Object.keys(window.__skSubs||{});for(var i=0;i<ids.length;i++){var pid=ids[i],e=window.__skSubs[pid];if(!e)continue;if(!koeAllowedSpeaker(e.uid)){try{if(e.sub&&e.sub.id)await skMe.unsubscribe(e.sub.id)}catch(_){}try{if(e.audio){if(e.audio.__koeGain){try{e.audio.__koeGain.disconnect()}catch(_){}}e.audio.srcObject=null;e.audio.remove()}}catch(_){}try{var arr=window.__koeRemoteAudio[e.uid]||[];window.__koeRemoteAudio[e.uid]=arr.filter(function(x){return x!==e.audio})}catch(_){}delete window.__skSubs[pid];skSubscribedPubIds.delete(pid);try{callLog("購読解除(聞き専に変更): user "+e.uid)}catch(_){}}}}catch(e){}};
// 自分のpublish()完了を待たずに先に相手の音声購読を開始する(以前は自分のpublish後だったため、
// マイク許可待ち/ICEネゴシエーション中は相手の声が聞こえなかった。「参加してもすぐ音が聞こえない」の再発対応)。
skRoom.onStreamPublished.add(async e=>{updateCallGrid();/* 誰かが配信を始めた=昇格の可能性。名簿を即時更新してから購読判定 */try{await refreshRoomStateNow()}catch(_){}skSubscribeToPublication(e.publication);try{window.__koeSyncSubs&&window.__koeSyncSubs()}catch(_){}});
// 入室時点で既に配信中の相手(自分より先に枠にいた人)は onStreamPublished が発火しないため、
// 既存publicationsを走査して即座に購読する。
try{(skRoom.publications||[]).forEach(skSubscribeToPublication);}catch(e){}
skMyPub=null;try{const mine=skLocalStream.track?new MediaStream([skLocalStream.track]):null;mine&&addSpeakAnalyser(window.__myUserId,mine)}catch(e){}await koeSyncPublish(true);startCallTimer(),callSetStatus("接続完了"),koeVibrate(40);{const cv=document.querySelector(".callv2");cv&&cv.classList.add("connected")}try{const cmEl2=document.getElementById("callMascot");if(cmEl2)cmEl2.style.display="none";}catch(e){}
skRoom.onMemberJoined.add(e=>{try{document.getElementById("callMemberCount").textContent=skRoom.members.length}catch(_){}try{var nm=callMemberName(e&&e.member);var lu=callMemberUid(e&&e.member);if(nm){try{csSeen(lu,nm);csEvent("join",lu,nm)}catch(_e){}toast(" "+nm+" が入室しました");callLog("入室: "+nm)}}catch(_){}try{window.__pollRoomState&&window.__pollRoomState()}catch(_){}updateCallGrid()}),skRoom.onMemberLeft.add(e=>{try{document.getElementById("callMemberCount").textContent=skRoom.members.length}catch(_){}try{var nm=callMemberName(e&&e.member);if(nm){var ownerUid=window.__callOwnerUid||window.__roomRoster&&window.__roomRoster.owner||0;var lu=callMemberUid(e&&e.member);try{csEvent("leave",lu,nm)}catch(_e){}if(lu&&lu===ownerUid){toast(" 主催者が退出しました。枠が終了した可能性があります");callLog("主催者退出(枠終了の可能性)")}else{toast(" "+nm+" が退出しました");callLog("退出: "+nm)}}}catch(_){}try{window.__pollRoomState&&window.__pollRoomState()}catch(_){}updateCallGrid()});try{skRoom.onStreamUnpublished.add(()=>{updateCallGrid();try{refreshRoomStateNow()}catch(_){}})}catch(e){}document.getElementById("callLeaveBtn").disabled=!1;{const cv=document.querySelector(".callv2");cv&&cv.classList.add("connected")}try{const __st=document.getElementById("callStatus");if(__st){__st.textContent="";}const __cm=document.getElementById("callMascot");if(__cm)__cm.style.display="none";}catch(e){}try{document.body.classList.add("in-call")}catch(e){}if(sfx("join"),window.AndroidApi&&window.AndroidApi.setInCall)try{window.AndroidApi.setInCall(!0)}catch(e){}}catch(err){callSetStatus("エラー: "+(err&&err.message?err.message:err)),console.error(err)}else callSetStatus("エラー: SkyWay SDKが読み込まれていません(ネットワーク/CDNを確認)")}async function teardownCall(notifyServer){if(notifyServer){try{csSave()}catch(e){}}try{document.body.classList.remove("in-call")}catch(e){}stopCallTimer();try{skMe&&await skMe.leave()}catch(e){}try{skRoom&&await skRoom.dispose()}catch(e){}try{skLocalStream&&skLocalStream.track&&skLocalStream.track.stop()}catch(e){}window.__koeRemoteAudio={};window.__koeGains={};window.__skSubs={};skMyPub=null;if(document.querySelectorAll("#remoteAudios audio").forEach(a=>a.remove()),notifyServer&&skCurrentRoomId)try{await callApi("room_leave",skCurrentRoomId)}catch(e){}stopSpeakAnalysers();{const cv=document.querySelector(".callv2");cv&&cv.classList.remove("connected")}try{const cmEl3=document.getElementById("callMascot");if(cmEl3)cmEl3.style.display="none";}catch(e){}if(skRoom=null,skMe=null,skLocalStream=null,skMuted=!1,skMyPub=null,window.__roomRoster=null,window.__rosterSig=null,window.__prevApplicantUids=null,window.__prevSpeakerUids=null,window.__prevAllUids=null,window.__prevMuteState=null,window.__justRaisedUids=null,window.__justPromotedUids=null,window.AndroidApi&&window.AndroidApi.setInCall)try{window.AndroidApi.setInCall(!1)}catch(e){}try{window.AndroidApi&&window.AndroidApi.stopCallAudio&&window.AndroidApi.stopCallAudio()}catch(e){}}async function leaveInWindowCall(){const ov=document.getElementById("callOverlay");ov&&(ov.style.display="none");{const s=document.getElementById("callStatus");s&&(s.textContent="退出しました")}{const b=document.getElementById("callLeaveBtn");b&&(b.disabled=!0)}{const b=document.getElementById("callMuteBtn");b&&(b.disabled=!0)}const banner=document.getElementById("returnToCallBanner");banner&&(banner.style.display="none");try{hideCallMini()}catch(e){}skCurrentRoomId=null,currentRoomId=null,currentRoomOwnerId=null;try{stopApplicantPolling()}catch(e){}try{stopRoomCommentPolling()}catch(e){}{const ci=document.querySelector('.rail-item[data-view="call"]');ci&&ci.classList.remove("in-call")}try{await teardownCall(!0)}catch(e){}}function setCallMuted(muted){if(!skLocalStream)return;skMuted=muted,sfx(muted?"mute":"unmute"),koeVibrate(25);try{csEvent(muted?"mute":"unmute",window.__myUserId||0,"あなた")}catch(e){}try{var __mb=document.getElementById("callMuteBtn");if(__mb){var __sp=__mb.querySelector("span");if(__sp)__sp.textContent=muted?"ミュート解除":"ミュート";__mb.classList.toggle("muted",!!muted)}}catch(e){}try{skLocalStream.track&&(skLocalStream.track.enabled=!skMuted)}catch(e){}try{skMyPub&&(skMuted?skMyPub.disable():skMyPub.enable())}catch(e){}try{updateCallGrid()}catch(e){}const b=document.getElementById("callMuteBtn");if(b){const s2=b.querySelector("span");s2&&(s2.textContent=skMuted?"解除":"マイク"),b.classList.toggle("muted",skMuted)}const m=document.getElementById("rcbMic");m&&(m.innerHTML=skMuted?'<svg class="ico" viewBox="0 0 24 24" fill="currentColor"><rect x="9" y="2.5" width="6" height="11" rx="3"/><path d="M6 11a6 6 0 0 0 12 0" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/><path d="M4 3l16 16" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>':'<svg class="ico" viewBox="0 0 24 24" fill="currentColor"><rect x="9" y="2.5" width="6" height="11" rx="3"/><path d="M6 11a6 6 0 0 0 12 0" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/><rect x="11" y="18" width="2" height="3.4" rx="1"/></svg>',m.classList.toggle("muted",skMuted))}function toggleCallMuteFromBanner(){setCallMuted(!skMuted)}function minimizeCall(){if(!skCurrentRoomId&&!skRoom)return;document.getElementById("callOverlay").style.display="none";try{initSpeakerBubbleDrag()}catch(e){}try{showCallMini()}catch(e){}}function reopenCall(){document.getElementById("callOverlay").style.display="flex";try{hideCallMini()}catch(e){}}function bindCallOverlayControls(){document.getElementById("callMuteBtn").onclick=()=>setCallMuted(!skMuted),document.getElementById("callLeaveBtn").onclick=async()=>{(function(){try{return"off"!==localStorage.getItem("koe_confirmleave")}catch(e){return!0}})()&&!await showConfirmModal("通話から退出しますか?")||(sfx("leave"),leaveInWindowCall())},document.getElementById("callCloseBtn").onclick=leaveInWindowCall;{const rb=document.getElementById("callRaiseHandBtn");rb&&(rb.onclick=doRaiseHand)}document.getElementById("callMinimizeBtn").onclick=()=>{minimizeCall();try{showPage("call");}catch(e){}},document.getElementById("callTitleBtn").onclick=async()=>{const t=document.getElementById("callTitleInput").value.trim();if(!t||!skCurrentRoomId)return;const r=await callApi("room_update_title",skCurrentRoomId,t);callLog(r.ok?"タイトルを変更しました: "+t:"タイトル変更失敗: "+JSON.stringify(r))},document.getElementById("callCloseRoomBtn").onclick=async()=>{if(!skCurrentRoomId)return;const r=await callApi("room_close",skCurrentRoomId);r.ok?(callLog("ルームを終了しました"),await leaveInWindowCall()):callLog("ルーム終了失敗: "+JSON.stringify(r))}}let currentChat=null;window.__koeChatEditMode=false;window.__koeChatSelected=new Set();
function updateChatBulkBar(){const bar=document.getElementById("chatBulkBar");if(!bar)return;const n=window.__koeChatSelected.size;bar.style.display=window.__koeChatEditMode?"flex":"none";const c=document.getElementById("chatBulkCount");if(c)c.textContent=n+"件選択中";const db=document.getElementById("chatBulkDeleteBtn");if(db)db.disabled=n===0;}
async function loadChats(){const list=document.getElementById("chatList");list.innerHTML=skeletonCards(4);const result=await callApi("get_chats");result.ok?result.rooms.length?(window.__chatRooms=result.rooms,list.innerHTML=result.rooms.map((r,i)=>`\n    <div class="card" data-chat-idx="${i}">
      ${window.__koeChatEditMode?`<input type="checkbox" class="chat-sel-cb" data-chat-idx="${i}" ${window.__koeChatSelected.has(String(r.chat_id))?"checked":""} style="width:20px;height:20px;margin-right:6px;flex:none;">`:""}\n      ${avatarHtml(r.name,r.icon_url)}\n      <div class="card-body">\n        <div class="card-name">${escapeHtml(r.name)} <span class="uid-tag">ID:${r.target_id}</span></div>\n        <div class="card-sub" title="${escapeHtml(r.last_sent_at||"")}">${r.last_message?escapeHtml(String(r.last_message).replace(/\\s+/g," ").slice(0,42)):'<span style="opacity:.5;">(メッセージなし)</span>'} <span style="opacity:.55;">· ${r.last_sent_at?relTime(r.last_sent_at):"-"}</span></div>\n      </div>\n    </div>`).join(""),updateChatBulkBar(),list.onclick=e=>{const card=e.target.closest("[data-chat-idx]");if(!card)return;const r=(window.__chatRooms||[])[+card.dataset.chatIdx];if(!r)return;if(window.__koeChatEditMode){const id=String(r.chat_id);window.__koeChatSelected.has(id)?window.__koeChatSelected.delete(id):window.__koeChatSelected.add(id);const cb=card.querySelector(".chat-sel-cb");if(cb)cb.checked=window.__koeChatSelected.has(id);updateChatBulkBar();return;}if(e.target.closest(".avatar,.uid-tag")){viewProfile(r.target_id);return;}openChat(r.chat_id,r.target_id,r.name,r.icon_url)}):list.innerHTML=`<div class="empty-msg">チャットがありません<br><button class="btn-secondary" style="margin-top:12px;width:auto;" onclick="document.getElementById('userSearchBtn')?.click()">ユーザーを探す</button><br><small style="opacity:.5;word-break:break-all;">応答: ${escapeHtml(result.raw||"")}</small></div>`:list.innerHTML=`<div class="empty-msg">読み込めませんでした<br><button class="btn-secondary" style="width:auto;margin-top:8px;" onclick="reloadCurrentView()">再試行</button></div>`}
(function(){
  function bind(){
    var eb=document.getElementById("chatEditBtn");
    if(eb&&!eb.__koeBound){eb.__koeBound=true;eb.addEventListener("click",function(){window.__koeChatEditMode=!window.__koeChatEditMode;window.__koeChatSelected.clear();eb.textContent=window.__koeChatEditMode?"完了":"編集";loadChats();});}
    var db=document.getElementById("chatBulkDeleteBtn");
    if(db&&!db.__koeBound){db.__koeBound=true;db.addEventListener("click",async function(){var ids=Array.from(window.__koeChatSelected);if(!ids.length)return;if(!await showConfirmModal(ids.length+"件のチャットを削除します。取り消せませんがよろしいですか?"))return;db.disabled=true;var r=await callApi("bulk_delete_chats",ids.join(","));toast(r&&r.ok?"削除しました":"削除に失敗しました",r&&r.ok?undefined:"error");window.__koeChatSelected.clear();window.__koeChatEditMode=false;var eb2=document.getElementById("chatEditBtn");if(eb2)eb2.textContent="編集";loadChats();});}
  }
  if(document.readyState==="loading"){document.addEventListener("DOMContentLoaded",bind)}else{bind()}
  setTimeout(bind,1200);
  document.addEventListener("click",function(){setTimeout(bind,300);},true);
})();
async function openChat(chatId,targetId,name,iconUrl){currentChat={chatId:chatId,targetId:targetId,name:name,icon:iconUrl};try{var head=document.getElementById("chatModalPartner");if(head){head.innerHTML=avatarHtml(name,iconUrl)+'<span id="chatModalTitle"></span>';head.onclick=function(){if(currentChat&&currentChat.targetId){window.__koeProfileOpenedFromChat=true;var cm=document.getElementById("chatModal");if(cm)cm.style.display="none";viewProfile(currentChat.targetId);}};}var t=document.getElementById("chatModalTitle");if(t)t.textContent=name;}catch(e){document.getElementById("chatModalTitle").textContent=name;}document.getElementById("chatModal").style.display="flex",await reloadMessages(),startChatPolling()}let chatPollTimer=null;function startChatPolling(){stopChatPolling(),chatPollTimer=setInterval(()=>{const m=document.getElementById("chatModal");currentChat&&m&&"none"!==m.style.display?reloadMessages(!0):stopChatPolling()},3e3)}function stopChatPolling(){chatPollTimer&&(clearInterval(chatPollTimer),chatPollTimer=null)}async function reloadMessages(isPoll){if(!currentChat)return;const box=document.getElementById("chatMessages");isPoll||(box.innerHTML='<div class="empty-msg">読み込み中...</div>');const __cid=currentChat.chatId;const result=await callApi("get_messages",currentChat.chatId,currentChat.targetId);if(!currentChat||currentChat.chatId!==__cid)return;if(!result.ok)return void(isPoll||(box.innerHTML='<div class="empty-msg">読み込み失敗</div>'));if(!result.messages.length)return void(isPoll||(box.innerHTML=`<div class="empty-msg">メッセージがありません<br><small style="opacity:.55;word-break:break-all;">${escapeHtml(result.raw||"")}</small></div>`));if(!currentChat.seen)currentChat.seen={};var __seen=currentChat.seen,__curSet={},__curIds=[];result.messages.forEach(function(m){if(m.id!=null){var k=String(m.id);__curSet[k]=1;__curIds.push(Number(m.id));var e=__seen[k];if(e){e.text=m.text;e.is_read=m.is_read;e.sent_at=m.sent_at;e.img=m.image_url;e.deleted=false}else{__seen[k]={id:m.id,text:m.text,is_read:m.is_read,sent_at:m.sent_at,img:m.image_url,mine:m.user_id===result.my_user_id,ord:currentChat.__ord=(currentChat.__ord||0)+1}}}});var __minId=__curIds.length?Math.min.apply(null,__curIds):0,__newlyDeleted=0;Object.keys(__seen).forEach(function(k){var e=__seen[k];if(!e.deleted&&!__curSet[k]&&Number(k)>=__minId){e.deleted=true;__newlyDeleted++}});if(isPoll&&__newlyDeleted>0){try{sfx("notify")}catch(e){}toast(" 相手がメッセージを削除しました")}var __list=Object.keys(__seen).map(function(k){return __seen[k]}).sort(function(a,b){var d=(Number(a.id)||0)-(Number(b.id)||0);return d!==0?d:(a.ord||0)-(b.ord||0)});const html=__list.map(m=>{const mine=m.mine;if(m.deleted)return`<div class="msg-row ${mine?"mine":"theirs"}">\n      <div class="msg-bubble deleted ${mine?"mine":"theirs"}"> 削除されたメッセージ<div class="msg-del-orig">${escapeHtml(m.text)}</div></div>\n    </div>`;return`<div class="msg-row ${mine?"mine":"theirs"}">\n      <div class="msg-bubble ${mine?"mine":"theirs"}">${m.img?('<img class="chat-msg-img" src="'+escapeHtml(m.img)+'" onerror="this.style.display=\'none\'">'):""}${m.text?escapeHtml(m.text):""}</div>\n      <div class="msg-meta">${mine&&m.is_read?'<span class="msg-read">既読</span>':""}${m.sent_at?`<span class="msg-time" title="${escapeHtml(m.sent_at)}">${relTime(m.sent_at)}</span>`:""}</div>\n    </div>`}).join("");if(isPoll&&box.__lastHtml===html)return;const atBottom=box.scrollHeight-box.scrollTop-box.clientHeight<80;box.innerHTML=html,box.__lastHtml=html;try{for(let i=result.messages.length-1;i>=0;i--){const m=result.messages[i];if(m.user_id!==result.my_user_id&&null!=m.id){callApi("mark_message_read",String(m.id));break}}}catch(e){}isPoll&&!atBottom||(box.scrollTop=box.scrollHeight)}async function sendChatMessage(){const input=document.getElementById("chatInput"),text=input.value.trim();if(!text||!currentChat)return;input.disabled=!0;const result=await callApi("send_message",currentChat.chatId,currentChat.targetId,text);input.disabled=!1,result.ok?(input.value="",await reloadMessages()):toast(`送信失敗: ${koeErrMsg(result)}`.slice(0,120),"error")}function closeChatModal(){document.getElementById("chatModal").style.display="none",currentChat=null,stopChatPolling()}async function loadCommunities(){const list=document.getElementById("communityList");list.innerHTML=skeletonCards(3);const result=await callApi("get_my_communities");result.ok?renderCommunities(result.communities,"参加中のコミュニティがありません",!0):list.innerHTML=`<div class="empty-msg">読み込めませんでした<br><button class="btn-secondary" style="width:auto;margin-top:10px;" onclick="reloadCurrentView()">再試行</button></div>`}async function loadCommunityCategories(){const box=document.getElementById("communityCategories");if(!box||box.dataset.loaded)return;const r=await callApi("get_community_categories");r.ok&&(r.categories||[]).length?(box.dataset.loaded="1",box.innerHTML='<button class="community-cat-chip active" data-cat="all">すべて</button>'+r.categories.map(c=>`<button class="community-cat-chip" data-cat="${c.id}">${escapeHtml(c.name)}</button>`).join(""),box.querySelectorAll(".community-cat-chip").forEach(c=>c.addEventListener("click",()=>{"all"===c.dataset.cat?(document.querySelectorAll(".community-cat-chip").forEach(x=>x.classList.toggle("active",x===c)),switchCommunityPageTab("mine"),loadCommunities(),sfx("tab")):searchCommunitiesByCategory(c.dataset.cat,c)}))):box.style.display="none"}async function searchCommunitiesByCategory(catId,chip){document.querySelectorAll(".community-cat-chip").forEach(x=>x.classList.toggle("active",x===chip)),switchCommunityPageTab("mine");const list=document.getElementById("communityList");list.innerHTML=skeletonCards(3);const r=await callApi("search_communities","",String(catId));r.ok?(renderCommunities(r.communities,"このカテゴリのコミュニティが見つかりません",!1),sfx("tab")):list.innerHTML='<div class="empty-msg">読み込み失敗</div>'}async function loadCommunitiesFeed(){const box=document.getElementById("communityFeed");box.innerHTML=skeletonCards(3);const result=await callApi("get_communities_feed","1");if(!result.ok)return void(box.innerHTML='<div class="empty-msg">読み込み失敗</div>');const posts=(result.posts||[]).filter(p=>!isFilteredPost(p));posts.length?box.innerHTML=posts.map(p=>`\n    <div class="timeline-card${p.voice_url?" has-voice":""}${p.is_explicit?" is-regulated":""}" data-pid="${p.id}" data-likes="${p.likes||0}">\n      <div class="tl-head">\n        <div class="tl-avatar" onclick='viewProfile(${p.user_id})' style="cursor:pointer;">${avatarHtml(p.name,p.icon_url)}</div>\n        <div class="tl-meta">\n          <div class="tl-name">${escapeHtml(p.name||"user "+p.user_id)}</div>\n          <div class="tl-time">${p.community_name?" "+escapeHtml(p.community_name)+" ・ ":""}${koeTimeLabel(p.created_at)}</div>\n        </div>\n      </div>\n      ${p.text?`<div class="tl-text">${linkify(p.text)}</div>`:""}\n    </div>`).join(""):box.innerHTML='<div class="empty-msg">参加中コミュニティの投稿がありません</div>'}function switchCommunityPageTab(tab){const mine=document.getElementById("communityList"),feed=document.getElementById("communityFeed");document.querySelectorAll(".community-tab-chip").forEach(c=>c.classList.toggle("active",c.dataset.ctab===tab)),"feed"===tab?(mine.style.display="none",feed.style.display="",loadCommunitiesFeed()):(feed.style.display="none",mine.style.display=""),sfx("tab")}function renderCommunities(communities,emptyMsg,isMember){const list=document.getElementById("communityList");communities&&communities.length?list.innerHTML=communities.map(c=>`\n    <div class="card" onclick="openCommunity(${c.id}, ${escAttr(JSON.stringify(c.name||""))}, ${isMember?"true":"false"})">\n      ${avatarHtml(c.name,c.icon_url)}\n      <div class="card-body">\n        <div class="card-name">${escapeHtml(c.name||"")} <span class="uid-tag">ID:${c.id}</span></div>\n        <div class="card-sub">${escapeHtml(c.description||"")} ・ <svg class="ico" viewBox="0 0 24 24" fill="currentColor"><circle cx="8" cy="9" r="3"/><circle cx="16.5" cy="10" r="2.4" opacity=".7"/><path d="M2.5 19a5.5 5.5 0 0 1 11 0 1 1 0 0 1-1 1h-9a1 1 0 0 1-1-1Z"/><path d="M15 14.6a5 5 0 0 1 6.5 4.4 1 1 0 0 1-1 1H16" opacity=".7"/></svg>${c.participant_count}人</div>\n      </div>\n    </div>\n  `).join(""):list.innerHTML=`<div class="empty-msg">${escapeHtml(emptyMsg||"コミュニティがありません")}</div>`}async function doCreateCommunity(){const name=document.getElementById("newCommunityName").value.trim(),desc=document.getElementById("newCommunityDesc").value.trim(),isOpen=document.getElementById("newCommunityOpen").checked;if(!name)return void toast("コミュニティ名を入力してください","error");const btn=document.getElementById("createCommunityBtn");btn.disabled=!0;const r=await callApi("create_community",name,desc,isOpen);btn.disabled=!1,r.ok?(toast("コミュニティを作成しました"),document.getElementById("newCommunityName").value="",document.getElementById("newCommunityDesc").value="",loadCommunities()):toast(`作成失敗: ${koeErrMsg(r)}`.slice(0,150),"error")}async function searchCommunities(){const kw=document.getElementById("communitySearchInput").value.trim();if(!kw)return void loadCommunities();const list=document.getElementById("communityList");list.innerHTML=skeletonCards(3),document.querySelectorAll(".community-cat-chip").forEach(x=>x.classList.toggle("active","all"===x.dataset.cat));const result=await callApi("search_communities",kw,"");result.ok?renderCommunities(result.communities,"該当するコミュニティがありません",!1):list.innerHTML=`<div class="empty-msg">検索に失敗しました。もう一度お試しください</div>`}async function doInspectPost(){const _ip=document.getElementById("inspectPostId");if(!_ip)return;const id=_ip.value.trim(),resultEl=document.getElementById("inspectResult");if(!id)return void toast("feed_post_idを入力してください","error");resultEl.textContent="取得中...";const result=await callApi("inspect_feed_post",parseInt(id,10));resultEl.textContent=JSON.stringify(result,null,2)}async function loadProfile(){const status=document.getElementById("profileStatus");status.textContent="読み込み中...";const result=await callApi("get_my_profile");if(!result.ok)return void(status.textContent=`読み込み失敗 (HTTP ${result.status||"?"}): ${escapeHtml(result.error||result.raw||koeErrMsg(result))}`);const p=result.profile||{};window.__koeMyBirthday=p.birthday||"";try{let a=getAccounts();const cur=currentAccountId(),i=a.findIndex(x=>x.user_id===cur);i>=0&&(p.name&&(a[i].name=p.name),p.icon_url&&(a[i].icon=p.icon_url),saveAccounts(a))}catch(e){}!p.name&&result.raw&&(status.textContent=`名前が空です。応答: ${escapeHtml(result.raw)}`);{const mh=document.getElementById("myHeaderImg"),card=document.querySelector(".profile-card");mh&&(p.header_url?(mh.src=p.header_url,mh.style.display="block",mh.style.cursor="pointer",mh.title="タップして保存",mh.onclick=function(){openLightbox(p.header_url)},card&&card.classList.add("has-header")):(mh.style.display="none",card&&card.classList.remove("has-header")))}document.getElementById("profileAvatar").innerHTML=avatarHtml(p.name,p.icon_url);{const myAvImg=document.querySelector("#profileAvatar img.avatar");if(myAvImg&&p.icon_url){myAvImg.style.cursor="pointer";myAvImg.title="タップして保存";myAvImg.onclick=function(e){e.stopPropagation();openLightbox(p.icon_url)}}}document.getElementById("profileNameDisplay").textContent=p.name,document.getElementById("profileUserId").textContent=p.user_id,document.getElementById("profileAge").textContent=null!=p.age?`${p.age}歳`:"年齢非公開",document.getElementById("profileFolloweeCount").textContent=p.followee_count,document.getElementById("profileFollowerCount").textContent=p.follower_count,document.getElementById("profileNameInput").value=p.name,document.getElementById("profileCommentInput").value=p.comment;try{koeUpdateBirthdayFieldVisibility()}catch(e){}{const pcd=document.getElementById("profileCommentDisplay");if(pcd)pcd.textContent=p.comment||"";koeApplyBioClamp("profileCommentDisplay");}var __onMy=(function(){try{var pg=document.getElementById("page-mypage");return !!(pg&&pg.classList.contains("active"))}catch(e){return true}})();window.__myPostsDeferred=!__onMy;if(__onMy){try{loadPostsInto("profileTabPosts",p.user_id||currentAccountId())}catch(e){}}const feeEl=document.getElementById("profileFolloweeStat")||document.getElementById("profileFolloweeCount"),ferEl=document.getElementById("profileFollowerStat")||document.getElementById("profileFollowerCount"),myId=p.user_id||currentAccountId();feeEl&&(feeEl.classList.add("pv-stat-click"),feeEl.onclick=()=>openFollowList(myId,"followees")),ferEl&&(ferEl.classList.add("pv-stat-click"),ferEl.onclick=()=>openFollowList(myId,"followers"));if(__onMy)loadBadgesInto("myBadges",null);try{const bal=__onMy?await callApi("get_account_balance"):null,el=document.getElementById("profileBalance");if(el&&bal&&bal.ok){const parts=[];bal.coin>=0&&parts.push(` ${fmtNum(bal.coin)} コイン`),bal.point>=0&&parts.push(` ${fmtNum(bal.point)} pt`),bal.good_talk_count>=0&&parts.push(` ${fmtNum(bal.good_talk_count)} 通話`),el.textContent=parts.join("  ・  "),el.style.display=parts.length?"block":"none"}}catch(e){}status.textContent=""}function koeUpdateBirthdayFieldVisibility(force){
  var setRow=document.getElementById("profileBirthdaySetRow"),wrap=document.getElementById("profileBirthdayFieldWrap"),chg=document.getElementById("profileBirthdayChangeBtn"),inp=document.getElementById("profileBirthdayInput");
  if(!setRow||!wrap)return;
  if(chg&&chg.__b!==1){chg.__b=1;chg.addEventListener("click",function(){setRow.style.display="none";wrap.style.display="block";if(inp)inp.focus();});}
  var already=!!window.__koeMyBirthday;
  var showInput=force==="input"||!already;
  if(showInput){setRow.style.display="none";wrap.style.display="block";}
  else{setRow.style.display="flex";wrap.style.display="none";if(inp)inp.value="";}
}
async function saveProfile(){const name=document.getElementById("profileNameInput").value.trim(),comment=document.getElementById("profileCommentInput").value.trim(),birthdayInput=document.getElementById("profileBirthdayInput"),birthday=birthdayInput.value.trim()||window.__koeMyBirthday||"",status=document.getElementById("profileStatus"),btn=document.getElementById("profileSaveBtn");if(!birthday){status.textContent="生年月日が未設定のため保存できません。上の「誕生日」欄に8桁(例: 19900101)で入力してから保存してください(最初の1回のみ必要です)";birthdayInput.focus();return;}btn.disabled=!0,status.textContent="保存中...";const result=await callApi("update_profile",name,comment,birthday);btn.disabled=!1,result.ok?(status.textContent="保存しました",window.__koeMyBirthday=birthday,birthdayInput.value="",await loadProfile()):status.textContent=`保存失敗: ${koeErrMsg(result)}`}async function uploadProfileOrHeaderImage(kind){kind=!0===kind?"header":!1===kind?"profile":kind||"profile";let input=document.getElementById("__profileImgInput");input||(input=document.createElement("input"),input.type="file",input.accept="image/*",input.id="__profileImgInput",input.style.display="none",document.body.appendChild(input)),input.value="",input.onchange=()=>{const file=input.files&&input.files[0];if(!file)return;const status=document.getElementById("profileImageStatus"),btn=document.getElementById("header"===kind?"uploadHeaderImageBtn":"background"===kind?"uploadBackgroundImageBtn":"uploadProfileImageBtn"),reader=new FileReader;reader.onload=ev=>{const img=new Image;img.onload=async()=>{try{const canvas=document.createElement("canvas"),ctx=canvas.getContext("2d");if("profile"!==kind){const dw=Math.min(img.width,1080),dh=Math.round(img.height*dw/img.width);canvas.width=dw,canvas.height=dh,ctx.drawImage(img,0,0,dw,dh)}else{const side=Math.min(img.width,img.height),sx=(img.width-side)/2,sy=(img.height-side)/2,target=Math.min(side,720);canvas.width=target,canvas.height=target,ctx.drawImage(img,sx,sy,side,side,0,0,target,target)}const dataUrl=canvas.toDataURL("image/png");btn&&(btn.disabled=!0),status.textContent="アップロード中...(数秒かかる場合があります)";const result=await callApi("upload_account_image",dataUrl,kind);if(result.ok){status.textContent=("header"===kind?"ヘッダー画像":"background"===kind?"背景画像":"プロフィール画像")+"を更新しました";try{sfx("success")}catch(e){}try{await loadProfile()}catch(e){}}else status.textContent="アップロード失敗: "+String(koeErrMsg(result))}catch(err){status.textContent="エラー: "+err}finally{btn&&(btn.disabled=!1)}},img.onerror=()=>{status.textContent="画像を読み込めませんでした"},img.src=ev.target.result},reader.readAsDataURL(file)},input.click()}async function loadRegulatedWords(){const list=document.getElementById("regulatedList");if(!list)return;list.innerHTML='<div class="empty-msg">読み込み中...</div>';const result=await callApi("get_regulated_words");result.ok&&result.words.length?list.innerHTML=result.words.slice().reverse().map(w=>`\n    <div class="card" style="cursor:default;">\n      <div class="card-body">\n        <div class="card-name">投稿ID: ${w.post_id} (投稿者 user_id: ${w.user_id})</div>\n        <div class="card-sub" style="white-space:normal;">${escapeHtml(w.text)}</div>\n        <div class="card-meta">is_explicit値: ${escapeHtml(String(w.is_explicit_value))} / ${escapeHtml(w.detected_at||"")}</div>\n      </div>\n    </div>\n  `).join(""):list.innerHTML='<div class="empty-msg">まだ検出された投稿はありません(タイムラインを開くと自動でチェックされます)</div>'}function renderRoomHistory(history){var logs=[];try{logs=JSON.parse(localStorage.getItem("koe_call_logs")||"[]")}catch(e){}window.__callLogs=logs;return history.slice().reverse().map(function(h){var oid=Number(h.owner_user_id),jt=Date.parse(h.joined_at)||0,li=-1,bestDiff=6*36e5;for(var i=0;i<logs.length;i++){if(Number(logs[i].ownerUid)!==oid)continue;var diff=Math.abs((logs[i].start||0)-jt);if(diff<bestDiff){bestDiff=diff;li=i}}var onclk=li>=0?"showCallLogDetail("+li+")":"viewProfile("+h.owner_user_id+")";var hint=li>=0?"タップで通話の詳細(参加者・発言・チャット等)":"タップでプロフィール";var badge=li>=0?'<span class="rh-detail-badge"> 詳細ログ</span>':"";var title=h.room_title?escapeHtml(h.room_title):h.owner_name?escapeHtml(h.owner_name):"名称不明の枠";var owner=h.owner_name?"オーナー "+escapeHtml(h.owner_name)+" ":"オーナー ";return'<div class="card" onclick="'+onclk+'">'+avatarHtml(h.owner_name||"",h.owner_icon||"")+'<div class="card-body"><div class="card-name">'+title+" "+badge+"</div>"+'<div class="card-sub">'+owner+'<span class="uid-tag">ID:'+h.owner_user_id+"</span></div>"+'<div class="card-sub">'+(relTime(h.joined_at)||escapeHtml(h.joined_at))+" に参加 ・ "+hint+"</div>"+"</div></div>"}).join("")}async function loadRoomHistory(){const list=document.getElementById("historyList");list.innerHTML='<div class="empty-msg">読み込み中...</div>';const result=await callApi("get_room_history");result.ok&&result.history.length?list.innerHTML=renderRoomHistory(result.history):list.innerHTML='<div class="empty-msg">まだ参加履歴がありません</div>'}async function loadActivityHeatmap(){const grid=document.getElementById("heatmapGrid"),totalEl=document.getElementById("heatmapTotal"),result=await callApi("get_activity_heatmap");if(!result.ok)return grid.innerHTML="",void(totalEl.textContent="取得に失敗しました");const counts=result.counts||{},today=new Date,days=[],start=new Date(today);start.setDate(start.getDate()-140+1),start.setDate(start.getDate()-start.getDay());for(let i=0;i<147;i++){const d=new Date(start);if(d.setDate(start.getDate()+i),d>today)break;const key=d.toISOString().slice(0,10);days.push({key:key,count:counts[key]||0})}grid.innerHTML=days.map(d=>`<div class="heatmap-cell" data-level="${0===d.count?0:1===d.count?1:d.count<=3?2:d.count<=6?3:4}" title="${d.key}: ${d.count}回参加"></div>`).join(""),totalEl.textContent=`直近140日で ${result.total} 回の通話参加`;try{const statsEl=document.getElementById("heatmapStats");if(statsEl){const keys=Object.keys(counts).filter(k=>counts[k]>0).sort(),activeDays=keys.length;let maxDay=0,maxKey="";for(const k of keys)counts[k]>maxDay&&(maxDay=counts[k],maxKey=k);const has=d=>counts[d.toISOString().slice(0,10)]>0;let cur=0;{const d=new Date;for(has(d)||d.setDate(d.getDate()-1);has(d);)cur++,d.setDate(d.getDate()-1)}let longest=0,run=0,prev=null;for(const k of keys){const cd=new Date(k);prev&&cd-prev===864e5?run++:run=1,run>longest&&(longest=run),prev=cd}const box=(label,val)=>`<div class="stat-box"><div class="stat-val">${val}</div><div class="stat-label">${label}</div></div>`;statsEl.innerHTML=box("現在の連続",cur+"日")+box("最長連続",longest+"日")+box("活動日数",activeDays+"日")+box("最多/日",maxDay+"回")}}catch(e){}}let livePulseTimer=null;async function refreshLivePulse(){if(document.hidden)return;const result=await callApi("get_live_pulse");if(result.ok){result.updated_at=Date.now();window.__koeLastPulse=result;const total=(result.open_rooms||0)+(result.online_receivers||0);document.getElementById("livePulseCount").textContent=String(total)+(result.capped?"+":""),document.getElementById("livePulse").title=`いま開いている通話ルーム数: ${total} (タップで詳細)`}}function startLivePulse(){refreshLivePulse(),livePulseTimer&&clearInterval(livePulseTimer),livePulseTimer=setInterval(refreshLivePulse,12e4)}let profileViewFollowState=null;window.__koeLastFollowList=null;async function openFollowList(userId,kind){window.__koeLastFollowList={userId:userId,kind:kind};const modal=document.getElementById("followListModal"),body=document.getElementById("followListBody");document.getElementById("followListTitle").textContent="followers"===kind?"フォロワー":"フォロー中",body.innerHTML=skeletonCards(4),modal.style.display="flex";const method="followers"===kind?"get_followers":"get_followees",result=await callApi(method,userId,1);result.ok?result.users.length?body.innerHTML=(function(){var us=result.users.slice();try{us.sort(function(a,b){var x=koeParseLoginAgo(a.login_status),y=koeParseLoginAgo(b.login_status);if(x===null&&y===null)return 0;if(x===null)return 1;if(y===null)return -1;return x-y})}catch(e){}try{if(String(userId)===String((typeof myUserId!=="undefined"&&myUserId)||window.__myUserId||"")&&kind!=="followers")koeActivityRecord(us)}catch(e){}return us})().map(u=>{var st=koeOnlineState(u.login_status);return `\n    <div class="card" onclick='closeFollowList(); viewProfile(${u.user_id})'>\n      ${avatarHtml(u.name,u.icon_url)}\n      <div class="card-body">\n        <div class="card-name">${escapeHtml(u.name)} <span class="uid-tag">ID:${u.user_id}</span>${koeSpamTag(u)}</div>${st.label?`<div class="card-sub"><span class="${st.cls}"></span>${escapeHtml(st.label)}</div>`:""}\n      </div>\n      <span style="font-size:16px;"><svg class="ico" viewBox="0 0 24 24" fill="currentColor"><circle cx="12" cy="8" r="4"/><path d="M4 20a8 8 0 0 1 16 0 1 1 0 0 1-1 1H5a1 1 0 0 1-1-1Z"/></svg></span>\n    </div>\n  `}).join(""):body.innerHTML=`<div class="empty-msg">${"followers"===kind?"フォロワーがいません":"フォローしている人がいません"}</div>`:body.innerHTML=`<div class="empty-msg">取得できませんでした</div>`}function closeFollowList(){document.getElementById("followListModal").style.display="none"}async function loadBadgesInto(containerId,userId){const el=document.getElementById(containerId);if(!el)return;el.innerHTML="";const result=await callApi("get_badges",userId??null);result.ok&&result.badges&&result.badges.length&&(el.innerHTML=result.badges.map(b=>{const label=escapeHtml(b.name||b.description||"バッジ");return b.icon_url?`<img class="badge-item" loading="lazy" decoding="async" src="${escAttr(b.icon_url)}" title="${label}" onerror="this.remove()">`:`<span class="badge-item badge-fallback" title="${label}"><svg class="ico" viewBox="0 0 24 24" fill="currentColor"><circle cx="12" cy="15" r="6"/><path d="M9 3l3 5 3-5" fill="none" stroke="currentColor" stroke-width="2"/></svg></span>`}).join(""))}function formatJoinDetailed(v){if(!v&&0!==v)return"";var sv=String(v),d=/^\d+$/.test(sv)?new Date(parseInt(sv,10)*(sv.length<=10?1e3:1)):new Date(sv);if(isNaN(d.getTime()))return"";var full=d.getFullYear()+"年"+(d.getMonth()+1)+"月"+d.getDate()+"日";var days=Math.floor((Date.now()-d.getTime())/864e5);if(days<0)days=0;var y=Math.floor(days/365),mo=Math.floor(days%365/30);var ago=y>0?y+"年"+(mo>0?mo+"ヶ月":""):mo>0?mo+"ヶ月":days+"日";return "アカウント開設 "+full+"（"+ago+"前）"}function formatJoinDate(v){if(!v&&0!==v)return"";let d;const sv=String(v);return d=/^\d+$/.test(sv)?new Date(parseInt(sv,10)*(sv.length<=10?1e3:1)):new Date(sv),isNaN(d.getTime())?"":`${d.getFullYear()}年${d.getMonth()+1}月${d.getDate()}日`}async function viewProfile(userId){const __pvSeq=(window.__pvSeq=(window.__pvSeq||0)+1);const modal=document.getElementById("profileViewModal"),followBtn=document.getElementById("profileViewFollowBtn"),badgeImg=document.getElementById("profileViewBadge");modal.style.display="flex",document.getElementById("profileViewAvatar").innerHTML='<div class="avatar">…</div>',document.getElementById("profileViewName").textContent="読み込み中...";try{let mb=document.getElementById("profileMuteBtn");const host=document.getElementById("profileViewReportBtn")?document.getElementById("profileViewReportBtn").parentElement:null;if(!mb&&host&&(mb=document.createElement("button"),mb.id="profileMuteBtn",mb.className="pv-menu-item",mb.style.width="auto",host.appendChild(mb)),mb){const muted=isMutedUser(userId);mb.textContent=muted?"ミュート解除":"この人をミュート",mb.onclick=()=>{const nowMuted=toggleMuteUser(userId);mb.textContent=nowMuted?"ミュート解除":"この人をミュート",toast(nowMuted?"ミュートしました(投稿を非表示)":"ミュートを解除しました"),sfx("toggle")}}let sb2=document.getElementById("profileShareBtn");!sb2&&host&&(sb2=document.createElement("button"),sb2.id="profileShareBtn",sb2.className="pv-menu-item",sb2.style.width="auto",sb2.textContent="共有",host.appendChild(sb2)),sb2&&(sb2.onclick=()=>{const txt=`https://koetomo.fun/users/${userId}`;if(window.AndroidApi&&window.AndroidApi.shareText)window.AndroidApi.shareText(txt);else if(navigator.share)navigator.share({text:txt}).catch(()=>{});else try{navigator.clipboard.writeText(txt),toast("コピーしました")}catch(e){}sfx("open")});let blb2=document.getElementById("profileViewBlacklistBtn");!blb2&&host&&(blb2=document.createElement("button"),blb2.id="profileViewBlacklistBtn",blb2.className="pv-menu-item",blb2.style.width="auto",blb2.style.color="var(--danger,#f23f43)",blb2.textContent="ブラックリスト申請",host.appendChild(blb2)),blb2&&(blb2.onclick=()=>{if(typeof window.__koeSubmitBlacklistReport==="function")window.__koeSubmitBlacklistReport(userId)});let chb2=document.getElementById("profileViewChatBtn");if(!chb2&&host){chb2=document.createElement("button"),chb2.id="profileViewChatBtn",chb2.className="pv-menu-item",chb2.style.width="auto",chb2.textContent="チャットを開く";var __firstMenuChild=host.firstElementChild;__firstMenuChild?host.insertBefore(chb2,__firstMenuChild):host.appendChild(chb2);}if(chb2)chb2.onclick=()=>{try{document.getElementById("profileViewModal").style.display="none"}catch(e){}var __pvn=(document.getElementById("profileViewName")&&document.getElementById("profileViewName").textContent)||"";var __pvic="";try{var __pvav=document.querySelector("#profileViewAvatar img.avatar");if(__pvav)__pvic=__pvav.src}catch(e){}if(typeof openChat==="function")openChat("",userId,__pvn,__pvic)};}catch(e){}{const uidEl=document.getElementById("profileViewUserId");uidEl.textContent=userId,uidEl.style.cursor="pointer",uidEl.title="タップでIDをコピー",uidEl.onclick=async()=>{try{await navigator.clipboard.writeText(String(userId)),toast("IDをコピーしました"),haptic(12)}catch(e){}}}document.getElementById("profileViewComment").textContent="";const _pvd=document.getElementById("profileViewDetails");_pvd&&(_pvd.innerHTML="");const _pvb=document.getElementById("profileViewBadges");_pvb&&(_pvb.innerHTML=""),badgeImg.style.display="none",followBtn.style.display="none";{const h=document.getElementById("profileViewHeader");h&&(h.style.display="none")}const result=await callApi("view_user_profile",userId);if(__pvSeq!==window.__pvSeq)return; /* 別の人のプロフィールを開き直した後に古い応答が届いた */if(!result.ok){document.getElementById("profileViewName").textContent="取得失敗";document.getElementById("profileViewComment").textContent=koeErrMsg(result);
/* ブロック検知(推定): プロフィール本体が「ユーザーが見つかりません/権限なし」なのに、一覧用の軽量APIでは
   その人が存在する → 相手からブロックされている可能性が高い(退会・停止でもこの形になることがある) */
try{var __raw=JSON.stringify(result.body||result.raw||"");var __code=(result.status===403||result.status===404||/見つかりません|権限/.test(__raw));if(__code){var rr=await callApi("resolve_users",String(userId));var u=rr&&rr.ok&&(rr.users||[]).find(function(x){return String(x.user_id)===String(userId)});var nm=u&&u.name;var cm=document.getElementById("profileViewComment");if(nm){document.getElementById("profileViewName").textContent=nm;cm.textContent="⚠ このユーザーのプロフィールを取得できません。相手からブロックされている可能性があります(アカウント停止・退会の場合も同じ表示になります)。";try{document.getElementById("profileViewAvatar").innerHTML=avatarHtml(nm,u.icon_url)}catch(e){}}else{cm.textContent="⚠ このユーザーは存在しないか、退会・停止された可能性があります。"}}}catch(e){}return}const p=result.profile;{const h=document.getElementById("profileViewHeader");h&&p.header_url&&(h.src=p.header_url,h.style.display="block",h.style.cursor="pointer",h.title="タップして保存",h.onclick=function(){openLightbox(p.header_url)},h.setAttribute("data-retry","0"),h.onerror=function(){var n=parseInt(h.getAttribute("data-retry")||"0",10);if(n<2){h.setAttribute("data-retry",n+1);setTimeout(function(){h.src=p.header_url+"?r="+(n+1)+"_"+Date.now()},700*(n+1))}else{h.style.display="none"}})}document.getElementById("profileViewAvatar").innerHTML=avatarHtml(p.name,p.icon_url);{const avImg=document.querySelector("#profileViewAvatar img.avatar");if(avImg&&p.icon_url){avImg.style.cursor="pointer";avImg.title="タップして保存";avImg.onclick=function(e){e.stopPropagation();openLightbox(p.icon_url)}}}document.getElementById("profileViewName").textContent=p.name,document.getElementById("profileViewComment").textContent=p.comment||"(コメントなし)";koeApplyBioClamp("profileViewComment");{const lbl=document.getElementById("profileViewPostsLabel");if(lbl)lbl.style.display="block"}loadPostsInto("profileViewPosts",p.user_id);const dv=document.getElementById("profileViewDetails");if(dv){const meta=[];null!=p.age&&""!==p.age&&meta.push(`${p.age}歳`),p.gender&&meta.push(escapeHtml(p.gender)),p.area_name&&meta.push(escapeHtml(p.area_name)),(p.level!=null&&""!==String(p.level))&&meta.push("Lv."+escapeHtml(String(p.level))),(p.status_message||p.mood)&&meta.push(escapeHtml(String(p.status_message||p.mood))),p.birthday&&meta.push("誕生日 "+escapeHtml(String(p.birthday))),p.login_status&&(function(){var st=koeOnlineState(p.login_status);meta.push(`<span class="${st.cls||"online-dot online-dot-off"}"></span>${escapeHtml(st.label||("最終オンライン "+p.login_status))}`)})(),p.is_following&&p.is_followed?meta.push("相互フォロー"):p.is_followed&&meta.push("あなたをフォロー中");try{if(p.liked_count!=null&&Number(p.liked_count)>0)meta.push("♥ もらったいいね "+fmtNum(Number(p.liked_count)));if(p.sms_verified===true)meta.push("SMS認証済み");var __av=p.age_verification;if(__av!=null&&__av!==""&&__av!==0&&__av!=="0"&&__av!==false){meta.push(String(__av)==="1"||String(__av).toLowerCase()==="verified"||String(__av).toLowerCase()==="approved"?"年齢確認済み":"年齢確認: "+escapeHtml(String(__av)))}if(p.suspended===true)meta.push('<span style="color:#f1436b;">⚠ 凍結中のアカウント</span>');var __ru=p.raw_user||{};try{if(koeSpamEnabled()){var __sp=koeSpamScore(Object.assign({},p,{icon_url:p.icon_url||(__ru.profile_picture_file_path||"")}));if(__sp.level)meta.unshift('<span style="color:'+(__sp.level==="high"?"#f1436b":"#e9b23c")+';">⚠ 業者の可能性'+(__sp.level==="high"?"（高）":"")+'：'+escapeHtml(__sp.reasons.join("・"))+'</span> <button type="button" class="theme-btn" style="width:auto;padding:2px 8px;font-size:11px;vertical-align:middle;" onclick="koeSpamPrompt('+Number(p.user_id||userId)+',{getAttribute:function(k){return k===\'title\'?'+JSON.stringify(escapeHtml(__sp.reasons.join("・")))+':'+JSON.stringify(escapeHtml(String(p.name||"")))+'}})">BANリストに申請</button>')}}catch(e){}try{if((p.age==null||p.age==="")&&__ru.birthday){var __bd=String(__ru.birthday).match(/(\d{4})\D(\d{1,2})\D(\d{1,2})/);if(__bd){var __b=new Date(+__bd[1],+__bd[2]-1,+__bd[3]),__n=new Date();var __a=__n.getFullYear()-__b.getFullYear();if(__n<new Date(__n.getFullYear(),__b.getMonth(),__b.getDate()))__a--;if(__a>=0&&__a<130)meta.unshift(__a+"歳（誕生日 "+__bd[1]+"/"+__bd[2]+"/"+__bd[3]+" から算出・本人は年齢非公開）")}}}catch(e){}if(__ru.total_free_coin!=null||__ru.total_paid_coin!=null)meta.push("コイン 無料 "+fmtNum(Number(__ru.total_free_coin||0))+" / 有料 "+fmtNum(Number(__ru.total_paid_coin||0)));if(__ru.total_point!=null)meta.push("ポイント "+fmtNum(Number(__ru.total_point||0)));if(__ru.is_blocking===true||Number(__ru.is_blocking)===1)meta.push('<span style="color:#f1436b;">⚠ あなたをブロック中</span>');if(__ru.warning_count!=null&&Number(__ru.warning_count)>0)meta.push('<span style="color:#e9b23c;">⚠ 運営からの警告 '+Number(__ru.warning_count)+'回</span>');if(__ru.cheering_talk&&__ru.cheering_talk.is_banned===true)meta.push('<span style="color:#f1436b;">応援トークBAN中</span>');if(__ru.settings&&typeof __ru.settings==="object"&&(__ru.settings.is_online_status_public===false||Number(__ru.settings.is_online_status_public)===0))meta.push("（オンライン状態は本人が非公開設定）")}catch(e){}{const j=formatJoinDetailed(p.created_at)||(formatJoinDate(p.created_at)?"アカウント開設 "+formatJoinDate(p.created_at):"");j&&meta.push(`<svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="5" width="18" height="16" rx="2"/><path d="M3 9h18M8 3v4M16 3v4" stroke-linecap="round"/></svg> ${j}`)}dv.innerHTML=`<div class="pv-stats">\n         <span class="pv-stat-click" onclick='openFollowList(${userId}, "followers")'><b>${fmtNum(p.follower_count??0)}</b> フォロワー</span>\n         <span class="pv-stat-click" onclick='openFollowList(${userId}, "followees")'><b>${fmtNum(p.followee_count??0)}</b> フォロー</span>\n         <span><b>${fmtNum(p.friend_count??0)}</b> 友達</span>${(p.post_count!=null||p.posts_count!=null)?`\n         <span><b>${fmtNum(p.post_count??p.posts_count??0)}</b> 投稿</span>`:""}\n       </div>`+(meta.length?`<div class="pv-meta">${meta.join(" ・ ")}</div>`:"")+koeProfileDetailHtml(p)}p.badge_icon_url&&(badgeImg.src=p.badge_icon_url,badgeImg.style.display="block"),profileViewFollowState={userId:userId,following:p.is_following,followed:!!p.is_followed,blocked:!!p.is_blocked},updateFollowButton(),updateBlockButtonUI(),followBtn.style.display="block",loadBadgesInto("profileViewBadges",userId)}function updateFollowButton(){
    const btn=document.getElementById("profileViewFollowBtn");
    if(!profileViewFollowState)return;
    var pending=profileViewFollowState.followed&&!profileViewFollowState.following;
    btn.textContent=profileViewFollowState.following?"フォロー中(解除)":pending?"フレンド申請を許可(フォローを返す)":"フォローする";
    btn.classList.toggle("btn-secondary",profileViewFollowState.following);
    btn.classList.toggle("btn-primary",!profileViewFollowState.following);
    var host=btn.parentElement;
    var rej=document.getElementById("profileViewFriendRejectBtn");
    if(pending){
      if(!rej&&host){
        rej=document.createElement("button");
        rej.id="profileViewFriendRejectBtn";
        rej.className="pv-menu-item";
        rej.style.width="auto";
        rej.textContent="拒否(このままにする)";
        btn.insertAdjacentElement("afterend",rej);
        rej.onclick=function(){
          try{document.getElementById("profileViewModal").style.display="none"}catch(e){}
          try{toast("フォローを返しませんでした")}catch(e){}
        };
      }
      if(rej)rej.style.display="";
    }else if(rej){
      rej.style.display="none";
    }
  }async function toggleFollow(){if(!profileViewFollowState)return;if(window.__followBusy)return;window.__followBusy=!0;const _fb=document.getElementById("profileViewFollowBtn");if(_fb)_fb.disabled=!0;try{const{userId:userId,following:following}=profileViewFollowState,result=following?await callApi("unfollow_user",userId):await callApi("follow_user",userId);result.ok?(profileViewFollowState.following=!following,updateFollowButton(),(function(){try{window.dispatchEvent(new CustomEvent("koe:follow-changed",{detail:{userId:userId,following:!following}}));}catch(e){}})(),following?toastAction("フォロー解除しました","元に戻す",async()=>{(await callApi("follow_user",userId)).ok&&(profileViewFollowState.following=!0,updateFollowButton(),toast("フォローに戻しました"))}):(sfx("follow"),toast("フォローしました"))):toast("操作に失敗しました","error")}catch(e){toast("操作に失敗しました","error")}finally{window.__followBusy=!1;if(_fb)_fb.disabled=!1}}async function reportUserFromProfile(){if(!profileViewFollowState)return;const userId=profileViewFollowState.userId,reason=await showInputModal("通報理由を入力","このユーザーを通報します");if(!reason)return;const result=await callApi("report_timeline_post",userId,reason);toast(result.ok?"通報しました":`通報失敗: ${koeErrMsg(result)}`.slice(0,120),result.ok?void 0:"error")}async function changePasswordAction(){const cur=document.getElementById("pwCurrent").value,nw=document.getElementById("pwNew").value,cf=document.getElementById("pwConfirm").value,status=document.getElementById("pwStatus");if(!cur||!nw)return void(status.textContent="現在のパスワードと新しいパスワードを入力してください");if(nw!==cf)return void(status.textContent="新しいパスワードが一致しません");if(nw.length<6)return void(status.textContent="新しいパスワードは6文字以上にしてください");status.textContent="変更中...";const r=await callApi("change_password",cur,nw,cf);if(r.ok){status.textContent="パスワードを変更しました";try{sfx("success")}catch(e){}document.getElementById("pwCurrent").value=document.getElementById("pwNew").value=document.getElementById("pwConfirm").value=""}else status.textContent="変更失敗 (status "+(r.status||"?")+"): "+String(koeErrMsg(r))}async function loadGiftHistory(){const box=document.getElementById("giftHistoryBody");if(!box)return;box.innerHTML=skeletonCards(2);const r=await callApi("get_gift_history");if(!r.ok)return void(box.innerHTML=`<div class="empty-msg">読み込めませんでした<br><button class="btn-secondary" style="width:auto;margin-top:8px;" onclick="reloadCurrentView()">再試行</button></div>`);const gifts=r.gifts||[];gifts.length?box.innerHTML=gifts.map(g=>{const sender=(g.user||g.from_user||g.sender||{}).name||g.sender_name||g.from_user_name||"",it=g.item||g.gift||{},item=it.name||g.item_name||g.gift_name||"ギフト",when=g.created_at||g.received_at||g.tipped_at||"",icon=it.icon_url||it.image_url||"";return`<div class="card">${icon?`<img loading="lazy" decoding="async" src="${escAttr(icon)}" style="width:40px;height:40px;border-radius:8px;object-fit:cover;" onerror="this.style.display='none'">`:""}<div class="card-body"><div class="card-name">${escapeHtml(item)}</div><div class="card-sub">${sender?"from "+escapeHtml(sender)+(when?" ・ ":""):""}${when?relTime(when):""}</div></div></div>`}).join(""):box.innerHTML='<div class="empty-msg">受け取ったギフトはありません</div>'}async function withdrawAccountAction(){const reason=(document.getElementById("withdrawReason").value||"").trim();if(!await showConfirmModal("本当にアカウントを退会しますか?この操作は取り消せません。"))return;if(!await showConfirmModal("最終確認: 退会するとアカウント・投稿・フォロー等がすべて失われます。実行しますか?"))return;const r=await callApi("withdraw_account",reason);r.ok?(toast("退会しました"),setTimeout(()=>{try{location.reload()}catch(e){}},800)):toast("退会に失敗しました (status "+(r.status||"?")+")","error")}async function loadUserSettings(){const box=document.getElementById("privacySettingsBody");if(!box)return;box.innerHTML='<div class="empty-msg">読み込み中...</div>';const result=await callApi("get_user_settings");if(!result.ok)return void(box.innerHTML=`<div class="empty-msg">読み込み失敗 (status ${result.status||"?"})</div>`);const s=result.settings||{};box.innerHTML=[["random_match_enabled","ランダムマッチングを許可","オフにすると、知らない相手とのランダム通話マッチングを受けなくなります"],["is_online_status_public","オンライン状態を公開",""],["is_read_receipt_public","既読を公開","オフにすると相手に既読が表示されません"],["is_my_age_public","年齢を公開",""],["is_follow_list_public","フォロー一覧を公開",""],["is_follower_list_public","フォロワー一覧を公開",""],["timeline_image_enabled","タイムラインの画像を表示",""]].map(([key,label,desc])=>`\n    <label class="check-row" style="margin-top:10px;"><input type="checkbox" data-setting="${key}" ${s[key]?"checked":""}> ${label}</label>\n    ${desc?`<div class="card-sub" style="margin:-2px 0 2px 26px;line-height:1.3;">${desc}</div>`:""}\n  `).join(""),box.querySelectorAll("input[data-setting]").forEach(inp=>{inp.addEventListener("change",async()=>{const changes={};changes[inp.dataset.setting]=inp.checked;const r=await callApi("set_user_settings",JSON.stringify(changes));if(r.ok){toast("設定を更新しました");try{sfx("success"),haptic(8)}catch(e){}}else toast("更新に失敗しました (status "+(r.status||"?")+")","error"),inp.checked=!inp.checked})})}async function loadBlockedUsers(){const box=document.getElementById("blockedList");if(!box)return;box.innerHTML=skeletonCards(2);const result=await callApi("get_block_list");result.ok?(window.__blockedUsers=result.users||[],window.__blockedUsers.length?(box.innerHTML=window.__blockedUsers.map((u,i)=>`\n    <div class="card">\n      <span onclick='viewProfile(${u.user_id})'>${avatarHtml(u.name,u.icon_url)}</span>\n      <div class="card-body" onclick='viewProfile(${u.user_id})'>\n        <div class="card-name">${escapeHtml(u.name||"user "+u.user_id)} <span class="uid-tag">ID:${u.user_id}</span></div>\n      </div>\n      <button class="btn-secondary" style="width:auto;padding:6px 14px;flex:none;" data-unblock-idx="${i}">解除</button>\n    </div>`).join(""),box.onclick=e=>{const btn=e.target.closest("[data-unblock-idx]");if(!btn)return;const u=(window.__blockedUsers||[])[+btn.dataset.unblockIdx];u&&unblockUserAction(u.user_id)}):box.innerHTML='<div class="empty-msg">ブロックしているユーザーはいません</div>'):box.innerHTML=`<div class="empty-msg">読み込み失敗 (status ${result.status||"?"})</div>`}async function unblockUserAction(userId){if(!await showConfirmModal("このユーザーのブロックを解除しますか?"))return;const result=await callApi("unblock_user",String(userId));if(result.ok){toast("ブロックを解除しました");try{sfx("success")}catch(e){}loadBlockedUsers()}else toast("解除に失敗しました: "+String(koeErrMsg(result)),"error")}function updateBlockButtonUI(){var btn=document.getElementById("profileViewBlockBtn");var blocked=!!(profileViewFollowState&&profileViewFollowState.blocked);if(btn){btn.textContent=blocked?"ブロック解除":"ブロック";btn.classList.toggle("btn-danger",!blocked);}var nameEl=document.getElementById("profileViewName");if(nameEl){var tag=document.getElementById("profileBlockedTag");if(blocked){if(!tag){tag=document.createElement("span");tag.id="profileBlockedTag";tag.textContent="ブロック中";tag.style.cssText="display:inline-block;margin-left:8px;background:#f23f43;color:#fff;font-size:11px;padding:2px 8px;border-radius:10px;vertical-align:middle;font-weight:600;";nameEl.appendChild(tag);}}else if(tag){tag.remove();}}}
async function blockUserFromProfile(){if(!profileViewFollowState)return;if(window.__blockBusy)return;window.__blockBusy=!0;const _bb=document.getElementById("profileViewBlockBtn");if(_bb)_bb.disabled=!0;try{const{userId:userId}=profileViewFollowState;if(profileViewFollowState.blocked){if(!await showConfirmModal("このユーザーのブロックを解除しますか?"))return;const r=await callApi("unblock_user",userId);if(r&&r.ok){toast("ブロックを解除しました");profileViewFollowState.blocked=false;updateBlockButtonUI();try{window.dispatchEvent(new CustomEvent("koe:block-changed",{detail:{userId:userId,blocked:false}}));}catch(e){}}else toast(`解除失敗: ${JSON.stringify((r&&(r.body||r.error))||"")}`.slice(0,120),"error");}else{if(!await showConfirmModal("このユーザーをブロックしますか?"))return;const result=await callApi("block_user",userId);if(result&&result.ok){toast("ブロックしました");profileViewFollowState.blocked=true;updateBlockButtonUI();try{window.dispatchEvent(new CustomEvent("koe:block-changed",{detail:{userId:userId,blocked:true}}));}catch(e){}}else toast(`ブロック失敗: ${JSON.stringify((result&&(result.body||result.error))||"")}`.slice(0,120),"error");}}finally{window.__blockBusy=!1;if(_bb)_bb.disabled=!1;}}let currentCommunity=null;async function openCommunity(communityId,name,isMember){currentCommunity={id:communityId,name:name,isMember:!!isMember},document.getElementById("communityModalTitle").textContent=name;{const d=document.getElementById("communityModalDesc");d&&(d.textContent="読み込み中…")}updateCommunityJoinBtn(),document.getElementById("communityModal").style.display="flex",callApi("get_community_info",communityId).then(r=>{const d=document.getElementById("communityModalDesc");if(d)if(r&&r.ok){const mem=r.member_count?`<span class="cm-members">${fmtNum(r.member_count)}人</span>`:"";d.innerHTML=mem+(r.description?" "+escapeHtml(r.description):""),d.innerHTML||(d.textContent="")}else d.textContent=""}).catch(()=>{const d=document.getElementById("communityModalDesc");d&&(d.textContent="")}),switchCommunityTab("posts"),await reloadCommunityPosts()}function updateCommunityJoinBtn(){const btn=document.getElementById("communityJoinBtn");btn&&currentCommunity&&(btn.textContent=currentCommunity.isMember?"退会":"参加",btn.classList.toggle("btn-danger",currentCommunity.isMember),btn.classList.toggle("btn-secondary",!currentCommunity.isMember))}async function toggleCommunityMembership(){if(!currentCommunity)return;const joining=!currentCommunity.isMember;if(!joining){if(!await showConfirmModal("このコミュニティを退会しますか?"))return;}const btn=document.getElementById("communityJoinBtn");if(btn)btn.disabled=!0;const r=await callApi(joining?"join_community":"leave_community",currentCommunity.id);if(btn)btn.disabled=!1;if(r.ok){currentCommunity.isMember=joining,updateCommunityJoinBtn(),toast(joining?"参加しました":"退会しました"),sfx(joining?"join":"leave");try{loadCommunities()}catch(e){}}else{const detail=koeErrMsg(r).slice(0,140);toast((joining?"参加":"退会")+"に失敗しました"+(r.status?" (status:"+r.status+")":"")+" "+detail,"error")}}function switchCommunityTab(tab){document.querySelectorAll(".community-tab").forEach(c=>c.classList.toggle("active",c.dataset.tab===tab));const posts=document.getElementById("communityPosts"),members=document.getElementById("communityMembers"),inputRow=document.getElementById("communityInputRow"),rules=document.getElementById("communityRules");"members"===tab?(posts.style.display="none",inputRow.style.display="none",members.style.display="block",rules&&(rules.style.display="none"),loadCommunityMembers()):"rules"===tab?(posts.style.display="none",inputRow.style.display="none",members.style.display="none",rules&&(rules.style.display="block",loadCommunityRules())):(posts.style.display="",inputRow.style.display="flex",members.style.display="none",rules&&(rules.style.display="none"))}async function loadCommunityRules(){const box=document.getElementById("communityRules");if(!currentCommunity||!box)return;box.innerHTML=skeletonCards(2);const r=await callApi("get_community_rules",currentCommunity.id);if(!r.ok)return void(box.innerHTML='<div class="empty-msg">ルールを取得できませんでした</div>');const rules=r.rules||[];rules.length?box.innerHTML=rules.map((ru,i)=>`<div class="rule-item"><div class="rule-num">${i+1}</div><div class="rule-body"><div class="rule-title">${escapeHtml(ru.title||"")}</div>${ru.text?`<div class="rule-text">${escapeHtml(ru.text)}</div>`:""}</div></div>`).join(""):box.innerHTML='<div class="empty-msg">このコミュニティにルールはありません</div>'}async function inviteCommunityMember(){if(!currentCommunity)return;const id=await showInputModal("招待するユーザーID","例: 4214303");if(!id)return;const num=String(id).replace(/[^0-9]/g,"");if(!num)return void toast("数字のIDを入力してください","error");const r=await callApi("invite_community_member",currentCommunity.id,num);r.ok?(toast("招待しました"),sfx("success")):toast(`招待に失敗: ${koeErrMsg(r).slice(0,100)}`,"error")}async function loadCommunityMembers(){const box=document.getElementById("communityMembersList")||document.getElementById("communityMembers");if(!currentCommunity)return;box.innerHTML=skeletonCards(4);const result=await callApi("get_community_members",currentCommunity.id),users=result.ok&&result.users?result.users:[];users.length?box.innerHTML=users.map(u=>`\n    <div class="card" onclick='viewProfile(${u.user_id})'>\n      ${avatarHtml(u.name,u.icon_url)}\n      <div class="card-body">\n        <div class="card-name">${escapeHtml(u.name||"")} <span class="uid-tag">ID:${u.user_id}</span></div>\n      </div>\n      <span style="font-size:16px;"><svg class="ico" viewBox="0 0 24 24" fill="currentColor"><circle cx="12" cy="8" r="4"/><path d="M4 20a8 8 0 0 1 16 0 1 1 0 0 1-1 1H5a1 1 0 0 1-1-1Z"/></svg></span>\n    </div>\n  `).join(""):box.innerHTML='<div class="empty-msg">メンバーを取得できませんでした</div>'}function closeCommunityModal(){document.getElementById("communityModal").style.display="none",currentCommunity=null}async function reloadCommunityPosts(){if(!currentCommunity)return;const box=document.getElementById("communityPosts");box.innerHTML='<div class="empty-msg">読み込み中...</div>';const result=await callApi("get_community_posts",currentCommunity.id);result.ok?result.posts.length?box.innerHTML=result.posts.map(p=>`\n    <div class="community-post">\n      <div class="card-name">${escapeHtml(p.name)} ${p.user_id?`<span class="uid-tag">ID:${p.user_id}</span>`:""}</div>\n      <div class="card-sub" style="white-space:normal;">${escapeHtml(p.text)}</div>\n      <div class="post-actions">\n        <span class="like-btn ${p.liked?"liked":""}" onclick='toggleLike(${p.id}, ${p.liked})'>\n          ${p.liked?'<svg class="ico" viewBox="0 0 24 24" fill="currentColor" style="color:#ff5a6a"><path d="M12 20.3 4.6 13c-2-2-2-5.2 0-7.1 1.9-1.8 4.9-1.6 6.7.3l.7.8.7-.8c1.8-1.9 4.8-2.1 6.7-.3 2 1.9 2 5.1 0 7.1L12 20.3Z"/></svg>':'<svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 20.3 4.6 13c-2-2-2-5.2 0-7.1 1.9-1.8 4.9-1.6 6.7.3l.7.8.7-.8c1.8-1.9 4.8-2.1 6.7-.3 2 1.9 2 5.1 0 7.1L12 20.3Z"/></svg>'} いいね\n        </span>\n        <span class="comment-btn" onclick="toggleComments(${p.id})"><svg class="ico" viewBox="0 0 24 24" fill="currentColor"><path d="M4 5.5A2.5 2.5 0 0 1 6.5 3h11A2.5 2.5 0 0 1 20 5.5v8A2.5 2.5 0 0 1 17.5 16H9l-4 3.6a.8.8 0 0 1-1.3-.6V5.5Z"/></svg> コメント</span>\n      </div>\n      <div id="comments-${p.id}" class="community-comments" style="display:none;"></div>\n    </div>\n  `).join(""):box.innerHTML='<div class="empty-msg">投稿がありません</div>':box.innerHTML='<div class="empty-msg">読み込み失敗</div>'}async function toggleLike(postId,currentlyLiked){await callApi("toggle_community_like",currentCommunity.id,postId,currentlyLiked),await reloadCommunityPosts()}function toggleComments(postId){const el=document.getElementById(`comments-${postId}`);el&&(el.style.display&&"none"!==el.style.display?el.style.display="none":(el.style.display="block",loadComments(postId)))}async function loadComments(postId){const el=document.getElementById(`comments-${postId}`);if(!el||!currentCommunity)return;el.innerHTML='<div class="empty-msg" style="padding:6px 0;">読み込み中...</div>';const result=await callApi("get_community_comments",currentCommunity.id,postId),list=result.ok&&result.comments?result.comments:[],commentsHtml=list.length?list.map(c=>`\n        <div class="community-comment">\n          <span class="cc-name" ${c.user_id?`onclick='viewProfile(${c.user_id})' style="cursor:pointer;"`:""}>${escapeHtml(c.name||"")}</span>\n          <span class="cc-text">${escapeHtml(c.text||"")}</span>\n        </div>`).join(""):'<div class="empty-msg" style="padding:4px 0;">コメントはありません</div>';el.innerHTML=commentsHtml+`<div class="cc-reply-row">\n       <input class="cc-reply-input" id="cc-input-${postId}" type="text" placeholder="コメントを書く...">\n       <button class="btn-secondary" style="width:auto;" onclick="submitComment(${postId})">送信</button>\n     </div>`;const inp=document.getElementById(`cc-input-${postId}`);inp&&inp.addEventListener("keydown",e=>{"Enter"===e.key&&submitComment(postId)})}async function submitComment(postId){const input=document.getElementById(`cc-input-${postId}`),text=input&&input.value.trim();if(!text)return;input.disabled=!0;(await callApi("create_community_comment",currentCommunity.id,postId,text)).ok?(toast("コメントしました"),loadComments(postId)):(input.disabled=!1,toast("コメント失敗","error"))}async function submitCommunityPost(){const input=document.getElementById("communityPostInput"),text=input.value.trim();if(!text||!currentCommunity)return;input.disabled=!0;const result=await callApi("create_community_post",currentCommunity.id,text);input.disabled=!1,result.ok?(input.value="",await reloadCommunityPosts()):toast(`投稿失敗: ${koeErrMsg(result)}`.slice(0,120),"error")}window.addEventListener("pywebviewready",async()=>{try{if(getPin&&getPin())showPinLock("unlock")}catch(e){}if(window.__koeInited)return;window.__koeInited=!0;try{__initUiExtras()}catch(e){console.error("initUiExtras",e)}["pointerdown","touchstart","mousedown","keydown"].forEach(ev=>document.addEventListener(ev,function(){try{const c=sfxCtx();c&&"suspended"===c.state&&c.resume()}catch(e){}},{capture:!0}));try{setupPullToRefresh()}catch(e){}document.getElementById("loginBtn").addEventListener("click",doLogin),document.getElementById("tokenLoginBtn").addEventListener("click",doTokenLogin),document.getElementById("showTokenBtn").addEventListener("click",showSessionToken);{const ab=document.getElementById("addAccountBtn");ab&&ab.addEventListener("click",async()=>{await showConfirmModal("別のアカウントでログインします。今のアカウントは切替リストに保存されます。")&&(await saveCurrentAccount(),await api().logout(),showScreen("loginScreen"))})}document.querySelectorAll(".notif-kind-chip").forEach(c=>c.addEventListener("click",()=>loadNotifications(c.dataset.kind))),document.getElementById("callChatSendBtn").addEventListener("click",sendRoomComment),document.getElementById("callChatInput").addEventListener("keydown",e=>{"Enter"===e.key&&(e.ctrlKey||e.metaKey)&&(e.preventDefault(),sendRoomComment())});{const ta=document.getElementById("callChatInput");if(ta){const grow=()=>{ta.style.height="auto";ta.style.height=Math.min(ta.scrollHeight,130)+"px"};ta.addEventListener("input",grow);ta.addEventListener("focus",grow)}}{const b=document.getElementById("callSpeakerBtn");b&&b.addEventListener("click",toggleCallSpeaker)}{const b=document.getElementById("callChatToggle");b&&b.addEventListener("click",()=>{toggleCallPanel("callChatPanel","callChatToggle");try{window.__chatUnread=0;var bd=b.querySelector(".callv2-badge");if(bd)bd.style.display="none";var lg=document.getElementById("callChatLog");if(lg)lg.scrollTop=lg.scrollHeight}catch(e){}})}{const b=document.getElementById("callSettingsToggle");b&&b.addEventListener("click",()=>toggleCallPanel("callSettingsPanel","callSettingsToggle"))}{const l=document.getElementById("navOrderList");l&&l.addEventListener("click",e=>{const b=e.target.closest("button[data-move]");b&&moveNavItem(b.dataset.view,b.dataset.move)})}applyNavOrder();const bgInput=document.getElementById("bgCustomInput");bgInput&&bgInput.addEventListener("input",e=>setCustomBackground(e.target.value));const bgReset=document.getElementById("bgResetBtn");bgReset&&bgReset.addEventListener("click",resetCustomBackground),document.getElementById("loginPassword").addEventListener("keydown",e=>{"Enter"===e.key&&doLogin()}),document.getElementById("loginEmail")&&document.getElementById("loginEmail").addEventListener("keydown",e=>{"Enter"===e.key&&doLogin()}),document.getElementById("logoutBtn").addEventListener("click",doLogout),document.getElementById("switchToSignup").addEventListener("click",toggleAuthMode),document.getElementById("signupBtn").addEventListener("click",doSignup),document.querySelectorAll(".room-feed-chip").forEach(chip=>{chip.addEventListener("click",()=>switchRoomFeed(chip.dataset.feed))}),document.querySelectorAll(".timeline-feed-chip[data-feed]").forEach(chip=>{chip.addEventListener("click",()=>switchTimelineFeed(chip.dataset.feed))}),document.querySelectorAll(".rail-item").forEach(item=>{item.addEventListener("click",()=>{haptic(8),sfx("tab");const ov=document.getElementById("callOverlay");ov&&"flex"===ov.style.display&&"function"==typeof minimizeCall&&minimizeCall(),item.classList.contains("active")&&document.querySelectorAll(".content-body, .page").forEach(cb=>{try{cb.scrollTo({top:0,behavior:"smooth"})}catch(e){cb.scrollTop=0}}),showPage(item.dataset.view)}),item.addEventListener("keydown",e=>{if("Enter"===e.key||" "===e.key){e.preventDefault();const ov=document.getElementById("callOverlay");ov&&"flex"===ov.style.display&&"function"==typeof minimizeCall&&minimizeCall(),showPage(item.dataset.view)}})}),document.querySelectorAll(".chip").forEach(chip=>{chip.addEventListener("click",()=>loadReceivers(chip.dataset.kind))}),document.getElementById("refreshRoomsBtn").addEventListener("click",loadGroupRooms);{const si=document.getElementById("roomSearchInput");si&&si.addEventListener("input",applyRoomView)}{const ss=document.getElementById("roomSortSelect");ss&&ss.addEventListener("change",()=>{applyRoomView(),sfx("tab")})}document.getElementById("raiseHandBtn").addEventListener("click",doRaiseHand),document.getElementById("saveModerationBtn").addEventListener("click",saveModerationSettings),document.getElementById("autoApproveChk").addEventListener("change",e=>{e.target.checked&&(document.getElementById("autoRejectChk").checked=!1)}),document.getElementById("autoRejectChk").addEventListener("change",e=>{e.target.checked&&(document.getElementById("autoApproveChk").checked=!1)}),["themeToggleBtn","themeDarkBtn","themeLightBtn","themeBlackBtn"].forEach(id=>{const el=document.getElementById(id);el&&("themeToggleBtn"===id?el.addEventListener("click",cycleTheme):el.addEventListener("click",()=>setTheme("themeDarkBtn"===id?"dark":"themeLightBtn"===id?"light":"black")))}),document.querySelectorAll(".accent-swatch").forEach(btn=>{btn.addEventListener("click",()=>setAccentColor(btn.dataset.color))}),document.getElementById("accentCustomInput").addEventListener("input",e=>setAccentColor(e.target.value)),document.getElementById("createRoomBtn").addEventListener("click",doCreateRoom);{const jb=document.getElementById("joinOtherBtn");jb&&jb.addEventListener("click",joinOtherRoom)}bindCallOverlayControls(),document.getElementById("followListClose").addEventListener("click",closeFollowList),document.getElementById("chatModalClose").addEventListener("click",closeChatModal),document.getElementById("chatSendBtn").addEventListener("click",sendChatMessage),document.getElementById("chatInput").addEventListener("keydown",e=>{"Enter"===e.key&&sendChatMessage()}),document.getElementById("communitySearchBtn").addEventListener("click",searchCommunities),document.querySelectorAll(".community-tab-chip").forEach(c=>c.addEventListener("click",()=>switchCommunityPageTab(c.dataset.ctab))),document.getElementById("createCommunityBtn").addEventListener("click",doCreateCommunity),document.getElementById("communitySearchInput").addEventListener("keydown",e=>{"Enter"===e.key&&searchCommunities()}),document.getElementById("communityModalClose").addEventListener("click",closeCommunityModal),document.getElementById("communityPostBtn").addEventListener("click",submitCommunityPost);{const ib=document.getElementById("communityInviteBtn");ib&&ib.addEventListener("click",inviteCommunityMember)}document.getElementById("communityJoinBtn").addEventListener("click",toggleCommunityMembership),document.querySelectorAll(".community-tab").forEach(t=>{t.addEventListener("click",()=>switchCommunityTab(t.dataset.tab))});{const b=document.getElementById("inspectBtn");b&&b.addEventListener("click",doInspectPost)}document.getElementById("profileSaveBtn").addEventListener("click",saveProfile),document.getElementById("uploadProfileImageBtn").addEventListener("click",()=>uploadProfileOrHeaderImage("profile")),document.getElementById("uploadHeaderImageBtn").addEventListener("click",()=>uploadProfileOrHeaderImage("header"));{const bg=document.getElementById("uploadBackgroundImageBtn");bg&&bg.addEventListener("click",()=>uploadProfileOrHeaderImage("background"))}{const pb=document.getElementById("changePasswordBtn");pb&&pb.addEventListener("click",changePasswordAction)}{const wb=document.getElementById("withdrawBtn");wb&&wb.addEventListener("click",withdrawAccountAction)}{const b=document.getElementById("refreshRegulatedBtn");b&&b.addEventListener("click",loadRegulatedWords)}document.getElementById("refreshHistoryBtn").addEventListener("click",loadRoomHistory),(function(){var lb=document.getElementById("latencyBadge");if(lb)lb.addEventListener("click",function(){var m=document.getElementById("latencyGraphModal");if(m){m.style.display="flex";renderLatencyGraph();}});var lc=document.getElementById("latencyGraphClose");if(lc)lc.addEventListener("click",function(){var m=document.getElementById("latencyGraphModal");if(m)m.style.display="none";});})(),document.getElementById("headerUser").addEventListener("click",()=>{showPage("mypage");setTimeout(function(){try{if(typeof openMypageSettings==="function")openMypageSettings();var accList=document.getElementById("accountsList");var det=accList&&accList.closest("details");if(det){det.open=true;det.scrollIntoView({behavior:"smooth",block:"start"});}}catch(e){}},60);}),document.getElementById("profileViewModalClose").addEventListener("click",()=>{document.getElementById("profileViewModal").style.display="none"}),document.getElementById("profileViewFollowBtn").addEventListener("click",toggleFollow),document.getElementById("profileViewBlockBtn").addEventListener("click",blockUserFromProfile),document.getElementById("profileViewReportBtn").addEventListener("click",reportUserFromProfile),document.getElementById("composeFab").addEventListener("click",openComposeModal),document.getElementById("composeModalClose").addEventListener("click",closeComposeModal),document.getElementById("composeCancel").addEventListener("click",closeComposeModal),document.getElementById("composeSubmit").addEventListener("click",submitComposePost);{const tp=document.getElementById("composeTopic");tp&&tp.addEventListener("change",()=>{try{localStorage.setItem("koe_last_topic",tp.value)}catch(e){}})}document.getElementById("composeImageBtn").addEventListener("click",()=>document.getElementById("composeImageInput").click()),document.getElementById("composeImageInput").addEventListener("change",e=>{e.target.files&&e.target.files[0]&&loadComposeImage(e.target.files[0])}),document.getElementById("composeImageClear").addEventListener("click",clearComposeImage);{const eb=document.getElementById("composeEmojiBtn");eb&&eb.addEventListener("click",toggleEmojiPicker)}const loggedIn=await api().is_logged_in();if(loggedIn.logged_in){enterMain(loggedIn.user_name);}else{
  // ネイティブ側のセッションが無い/切れている場合でも、最後に選択したアカウントの保存トークンで
  // 確認ダイアログなしに即座に再ログインを試みる(「アプリ開いたら絶対即時に自動ログイン」対応)。
  var __autoOk=false;
  try{
    var __accs=getAccounts(),__cur=currentAccountId();
    var __acc=__accs.find(function(x){return x.user_id===__cur})||__accs[0];
    if(__acc&&__acc.token){
      var __r=await callApi("login_with_token",__acc.token,String(__acc.user_id));
      if(__r&&__r.ok){try{localStorage.setItem("koe_current_account",String(__acc.user_id))}catch(e){}enterMain(__acc.name);__autoOk=true;}
    }
  }catch(e){}
  if(!__autoOk)showScreen("loginScreen");
}});
// ==== KoeTomo追加実装: kick / コメントON-OFF / 招待 / join_trial (公式APKのstrings解析から発見した未実装エンドポイント) ====
(function(){
if(window.__koeExtraInit)return;window.__koeExtraInit=true;

window.koeKickParticipant=async function(targetId){
if(!currentRoomId||!targetId)return{ok:false};
const r=await callApi("room_kick_user",currentRoomId,String(targetId));
toast(r&&r.ok?"キックしました":"キックに失敗しました");
try{refreshRoomStateNow()}catch(e){}
return r;
};

let __koeCommentEnabled=true;
window.koeToggleRoomComment=async function(){
if(!currentRoomId)return{ok:false};
__koeCommentEnabled=!__koeCommentEnabled;
const r=await callApi("room_switch_comment_enabled",currentRoomId,__koeCommentEnabled);
toast(r&&r.ok?("コメント欄を"+(__koeCommentEnabled?"ONにしました":"OFFにしました")):"切替に失敗しました");
if(!(r&&r.ok))__koeCommentEnabled=!__koeCommentEnabled;
return r;
};

window.koeInviteToRoom=async function(targetId){
if(!currentRoomId||!targetId)return{ok:false};
const r=await callApi("room_invite",currentRoomId,String(targetId));
toast(r&&r.ok?"招待しました":"招待に失敗しました");
return r;
};

window.koeJoinRoomTrial=async function(ownerUserId){
const r=await callApi("room_join_trial",ownerUserId?String(ownerUserId):"");
toast(r&&r.ok?"お試し参加しました":"お試し参加に失敗しました");
return r;
};

function __koeIsRoomOwner(){
try{return typeof currentRoomOwnerId!=="undefined"&&typeof myUserId!=="undefined"&&currentRoomOwnerId&&myUserId&&Number(currentRoomOwnerId)===Number(myUserId)}catch(e){return false}
}

document.addEventListener("DOMContentLoaded",function(){
const commentBtn=document.getElementById("callCommentToggleBtn");
if(commentBtn)commentBtn.addEventListener("click",window.koeToggleRoomComment);

const inviteBtn=document.getElementById("profileViewInviteBtn");
if(inviteBtn){
inviteBtn.addEventListener("click",async function(){
if(typeof profileViewFollowState==="undefined"||!profileViewFollowState)return;
await window.koeInviteToRoom(profileViewFollowState.userId);
});
}

// プロフィールモーダルを開いた時、通話中かつオーナーなら「枠に招待」ボタンを表示する
const profileModal=document.getElementById("profileViewModal");
if(profileModal){
const mo=new MutationObserver(function(){
if(profileModal.style.display!=="none"&&inviteBtn){
inviteBtn.style.display=__koeIsRoomOwner()?"":"none";
}
});
mo.observe(profileModal,{attributes:true,attributeFilter:["style"]});
}

// 参加者アバターを長押し(オーナーのみ・自分以外)→確認の上キック
const list=document.getElementById("callParticipantList");
if(list){
let pressTimer=null,pressUid=null;
const start=function(e){
const av=e.target.closest&&e.target.closest(".callv2-pav");
if(!av)return;
pressUid=av.getAttribute("data-uid");
if(!pressUid)return;
pressTimer=setTimeout(function(){
if(!__koeIsRoomOwner())return;
if(typeof myUserId!=="undefined"&&String(pressUid)===String(myUserId))return;
showConfirmModal("この参加者をキックしますか?").then(function(ok){if(ok)window.koeKickParticipant(pressUid);});
},600);
};
const cancel=function(){if(pressTimer){clearTimeout(pressTimer);pressTimer=null}};
list.addEventListener("mousedown",start);
list.addEventListener("touchstart",start,{passive:true});
["mouseup","mouseleave","touchend","touchcancel"].forEach(function(ev){list.addEventListener(ev,cancel)});
}
});
})();

// ==== KoeTomo追加実装: コミュニティのトークルーム機能 (join/leave/kick/change_role/comment-toggle) ====
(function(){
if(window.__koeCommunityRoomInit)return;window.__koeCommunityRoomInit=true;

window.koeCurrentCommunityRoom=null; // {communityId, roomId, ownerId}

function escapeHtmlSafe(s){
try{return escapeHtml(s)}catch(e){return String(s==null?"":s).replace(/[&<>"']/g,function(c){return{"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]})}
}

function renderTalkRoomList(box, rooms, opts){
opts=opts||{};
const showCommunityName=!!opts.showCommunityName;
if(!rooms.length){box.innerHTML='<div class="empty-msg">現在開催中のトークルームはありません</div>';return}
box.innerHTML=rooms.map(function(rm){
const sub=(showCommunityName&&rm.community_name?escapeHtmlSafe(rm.community_name)+' ・ ':'')+escapeHtmlSafe(rm.owner_name||"")+' ・ 参加者 '+escapeHtmlSafe(rm.member_count||0)+'人';
return '<div class="card" style="display:flex;align-items:center;gap:10px;padding:10px;margin-bottom:8px;" data-room-id="'+escapeHtmlSafe(rm.id)+'" data-owner-id="'+escapeHtmlSafe(rm.owner_user_id)+'" data-community-id="'+escapeHtmlSafe(rm.community_id!=null?rm.community_id:"")+'">'
+'<img loading="lazy" decoding="async" src="'+escapeHtmlSafe(rm.owner_icon||"")+'" style="width:40px;height:40px;border-radius:50%;object-fit:cover;background:#333;" onerror="this.style.visibility=\'hidden\'">'
+'<div style="flex:1;min-width:0;">'
+'<div style="font-weight:bold;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">'+escapeHtmlSafe(rm.title||(rm.owner_name+" のルーム"))+'</div>'
+'<div style="font-size:12px;opacity:.7;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">'+sub+'</div>'
+'</div>'
+'<button class="btn-secondary koe-ctr-join" style="width:auto;padding:4px 12px;font-size:12px;flex-shrink:0;">参加</button>'
+'</div>';
}).join("");
box.querySelectorAll(".koe-ctr-join").forEach(function(btn){
btn.addEventListener("click",function(){
if(btn.disabled)return;
const card=btn.closest(".card");
const roomId=card.getAttribute("data-room-id");
const ownerId=card.getAttribute("data-owner-id");
const communityId=card.getAttribute("data-community-id")||(currentCommunity&&currentCommunity.id);
btn.disabled=true;
const orig=btn.textContent;
btn.textContent="参加中...";
window.koeJoinCommunityTalkRoomDirect(communityId,roomId,ownerId).finally(function(){
btn.disabled=false;btn.textContent=orig;
});
});
});
}

let __koeCommunityRoomsRefreshTimer=null;

async function loadCommunityTalkRooms(){
const box=document.getElementById("communityRoomsList");
if(!currentCommunity||!box)return;
if(!box.querySelector(".card"))box.innerHTML='<div class="empty-msg">読み込み中...</div>';
let r;
try{r=await callApi("get_community_talk_rooms",currentCommunity.id)}catch(e){r=null}
if(!r||!r.ok){
box.innerHTML='<div class="empty-msg">トークルームを取得できませんでした<br><button class="btn-secondary koe-retry" style="margin-top:8px;width:auto;padding:4px 14px;">再試行</button></div>';
const rb=box.querySelector(".koe-retry");
if(rb)rb.addEventListener("click",loadCommunityTalkRooms);
return;
}
renderTalkRoomList(box, r.talk_rooms||[], {showCommunityName:false});
}

async function loadAllCommunityTalkRooms(){
const box=document.getElementById("communityAllRooms");
if(!box)return;
if(!box.querySelector(".card"))box.innerHTML='<div class="empty-msg">読み込み中...</div>';
let r;
try{r=await callApi("get_participating_community_talk_rooms")}catch(e){r=null}
if(!r||!r.ok){
box.innerHTML='<div class="empty-msg">トークルームを取得できませんでした<br><button class="btn-secondary koe-retry" style="margin-top:8px;width:auto;padding:4px 14px;">再試行</button></div>';
const rb=box.querySelector(".koe-retry");
if(rb)rb.addEventListener("click",loadAllCommunityTalkRooms);
return;
}
renderTalkRoomList(box, r.talk_rooms||[], {showCommunityName:true});
}

function __koeStartCommunityRoomsAutoRefresh(fn){
__koeStopCommunityRoomsAutoRefresh();
__koeCommunityRoomsRefreshTimer=setInterval(function(){
if(document.hidden)return;
// 実在するコンテナで可視判定。コミュニティのモーダルもページも表示されていなければ自動更新を停止(画面遷移/タブ切替の取りこぼし対策)
var modal=document.getElementById("communityModal");
var modalOpen=modal&&modal.style.display!=="none";
var page=document.getElementById("page-community");
var pageActive=page&&page.classList.contains("active");
if(!modalOpen&&!pageActive){__koeStopCommunityRoomsAutoRefresh();return;}
fn();
},15000);
}
function __koeStopCommunityRoomsAutoRefresh(){
if(__koeCommunityRoomsRefreshTimer){clearInterval(__koeCommunityRoomsRefreshTimer);__koeCommunityRoomsRefreshTimer=null}
}
window.__koeStopCommunityRoomsAutoRefresh=__koeStopCommunityRoomsAutoRefresh;

window.koeJoinCommunityTalkRoomDirect=async function(communityId,roomId,ownerId){
if(!communityId||!roomId){toast("コミュニティ情報が取得できませんでした","error");return{ok:false}}
let jr;
try{jr=await callApi("join_community_talk_room",communityId,String(roomId))}catch(e){jr=null}
if(!jr||!jr.ok){
const reason=jr&&(jr.error||jr.status)?(" ("+(jr.error||jr.status)+")"):"";
toast("トークルームへの参加に失敗しました"+reason,"error");
return jr||{ok:false};
}
window.koeCurrentCommunityRoom={communityId:communityId,roomId:String(roomId),ownerId:ownerId};
try{document.getElementById("communityModal").style.display="none"}catch(e){}
try{await joinCallFor(ownerId)}catch(e){
toast("通話への参加に失敗しました");
try{await callApi("leave_community_talk_room",communityId,String(roomId))}catch(e2){}
window.koeCurrentCommunityRoom=null;
}
return jr;
};

window.koeJoinCommunityTalkRoom=async function(roomId,ownerId){
if(!currentCommunity)return{ok:false};
return window.koeJoinCommunityTalkRoomDirect(currentCommunity.id,roomId,ownerId);
};

window.koeLeaveCommunityTalkRoom=async function(){
const cur=window.koeCurrentCommunityRoom;
if(!cur)return{ok:false};
const r=await callApi("leave_community_talk_room",cur.communityId,cur.roomId);
window.koeCurrentCommunityRoom=null;
return r;
};

// 元のswitchCommunityTab/teardownCallをラップして「トークルーム」タブとルーム退出処理を追加
if(typeof window.switchCommunityTab==="function"){
const origSwitchCommunityTab=window.switchCommunityTab;
window.switchCommunityTab=function(tab){
const roomsBox=document.getElementById("communityRoomsList");
__koeStopCommunityRoomsAutoRefresh();
if(tab==="rooms"){
document.querySelectorAll(".community-tab").forEach(function(c){c.classList.toggle("active",c.dataset.tab==="rooms")});
const posts=document.getElementById("communityPosts"),members=document.getElementById("communityMembers"),inputRow=document.getElementById("communityInputRow"),rules=document.getElementById("communityRules");
if(posts)posts.style.display="none";
if(inputRow)inputRow.style.display="none";
if(members)members.style.display="none";
if(rules)rules.style.display="none";
if(roomsBox)roomsBox.style.display="block";
loadCommunityTalkRooms();
__koeStartCommunityRoomsAutoRefresh(loadCommunityTalkRooms);
return;
}
if(roomsBox)roomsBox.style.display="none";
return origSwitchCommunityTab(tab);
};
}

if(typeof window.switchCommunityPageTab==="function"){
const origSwitchCommunityPageTab=window.switchCommunityPageTab;
window.switchCommunityPageTab=function(tab){
const allRoomsBox=document.getElementById("communityAllRooms");
__koeStopCommunityRoomsAutoRefresh();
if(tab==="rooms"){
document.querySelectorAll(".community-tab-chip").forEach(function(c){c.classList.toggle("active",c.dataset.ctab==="rooms")});
const mine=document.getElementById("communityList"),feed=document.getElementById("communityFeed");
if(mine)mine.style.display="none";
if(feed)feed.style.display="none";
if(allRoomsBox)allRoomsBox.style.display="";
loadAllCommunityTalkRooms();
__koeStartCommunityRoomsAutoRefresh(loadAllCommunityTalkRooms);
try{sfx("tab")}catch(e){}
return;
}
if(allRoomsBox)allRoomsBox.style.display="none";
return origSwitchCommunityPageTab(tab);
};
}

{
const modalCloseBtn=document.getElementById("communityModalClose");
if(modalCloseBtn)modalCloseBtn.addEventListener("click",__koeStopCommunityRoomsAutoRefresh);
}

if(typeof window.teardownCall==="function"){
const origTeardownCall=window.teardownCall;
window.teardownCall=function(notifyServer){
if(window.koeCurrentCommunityRoom){
try{window.koeLeaveCommunityTalkRoom()}catch(e){}
}
return origTeardownCall(notifyServer);
};
}

// 通話中のキック/コメントON-OFFは、コミュニティのトークルーム中ならコミュニティ側APIを使う
const origKoeKickParticipant=window.koeKickParticipant;
window.koeKickParticipant=async function(targetId){
const cur=window.koeCurrentCommunityRoom;
if(cur){
if(!targetId)return{ok:false};
const r=await callApi("kick_community_talk_room_user",cur.communityId,cur.roomId,String(targetId));
toast(r&&r.ok?"キックしました":"キックに失敗しました");
try{refreshRoomStateNow()}catch(e){}
return r;
}
return origKoeKickParticipant?origKoeKickParticipant(targetId):{ok:false};
};

const origKoeToggleRoomComment=window.koeToggleRoomComment;
let __koeCommunityCommentEnabled=true;
window.koeToggleRoomComment=async function(){
const cur=window.koeCurrentCommunityRoom;
if(cur){
__koeCommunityCommentEnabled=!__koeCommunityCommentEnabled;
const r=await callApi("switch_community_talk_room_comment_enabled",cur.communityId,cur.roomId,__koeCommunityCommentEnabled);
toast(r&&r.ok?("コメント欄を"+(__koeCommunityCommentEnabled?"ONにしました":"OFFにしました")):"切替に失敗しました");
if(!(r&&r.ok))__koeCommunityCommentEnabled=!__koeCommunityCommentEnabled;
return r;
}
return origKoeToggleRoomComment?origKoeToggleRoomComment():{ok:false};
};

// 投稿詳細の保存(ブックマーク)ボタン
window.__koeCurrentFeedPostId=null;
window.__koeCurrentFeedPostBookmarked=false;
if(typeof window.openPostDetail==="function"){
const origOpenPostDetail=window.openPostDetail;
window.openPostDetail=function(evt,postId){
window.__koeCurrentFeedPostId=postId;
const btn=document.getElementById("pdBookmarkBtn");
if(btn)btn.style.display="none";
const r=origOpenPostDetail(evt,postId);
callApi("get_feed_post",String(postId)).then(function(pr){
if(pr&&pr.ok&&pr.post&&btn){
window.__koeCurrentFeedPostBookmarked=!!pr.post.bookmarked;
btn.style.display="";
btn.textContent=window.__koeCurrentFeedPostBookmarked?"★ 保存済み":"☆ 保存";
}
}).catch(function(){});
return r;
};
}
{
const pdBookmarkBtn=document.getElementById("pdBookmarkBtn");
if(pdBookmarkBtn)pdBookmarkBtn.addEventListener("click",async function(){
if(!window.__koeCurrentFeedPostId)return;
pdBookmarkBtn.disabled=true;
const wasBookmarked=window.__koeCurrentFeedPostBookmarked;
const r=await callApi("toggle_feed_post_bookmark",String(window.__koeCurrentFeedPostId),wasBookmarked);
pdBookmarkBtn.disabled=false;
if(r&&r.ok){
window.__koeCurrentFeedPostBookmarked=!wasBookmarked;try{koeBmSet(window.__koeCurrentFeedPostId,!wasBookmarked)}catch(e){}
pdBookmarkBtn.textContent=window.__koeCurrentFeedPostBookmarked?"★ 保存済み":"☆ 保存";
toast(window.__koeCurrentFeedPostBookmarked?"保存しました":"保存を解除しました");
}else{
toast("保存の切替に失敗しました"+(r&&r.status?" (HTTP "+r.status+")":""),"error");
}
});
}

// 公式ヘルプ・サポートのリンク一覧
async function loadOfficialLinks(){
const box=document.getElementById("officialLinksList");
if(!box)return;
if(!box.querySelector(".card"))box.innerHTML='<div class="empty-msg">読み込み中...</div>';
let r;
try{r=await callApi("get_official_links")}catch(e){r=null}
if(!r||!r.ok||!r.links||!r.links.length){box.innerHTML='<div class="empty-msg">リンクを取得できませんでした</div>';return}
box.innerHTML=r.links.map(function(l){
return '<div class="card koe-official-link" style="cursor:pointer;" data-url="'+escapeHtmlSafe(l.url)+'">'
+'<div class="card-body">'
+'<div class="card-name">'+escapeHtmlSafe(l.title||"")+' <span class="uid-tag">'+escapeHtmlSafe(l.category||"")+'</span></div>'
+'<div class="card-sub" style="white-space:normal;opacity:.7;">'+escapeHtmlSafe(l.url)+'</div>'
+'</div>'
+'<span style="font-size:18px;opacity:.6;"><svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5"/></svg></span>'
+'</div>';
}).join("");
box.querySelectorAll(".koe-official-link").forEach(function(card){
card.addEventListener("click",function(){
const url=card.getAttribute("data-url");
if(!url)return;
try{
if(window.AndroidApi&&window.AndroidApi.openUrl){window.AndroidApi.openUrl(url)}
else{window.open(url,"_blank")}
}catch(e){try{window.open(url,"_blank")}catch(e2){}}
});
});
}
document.addEventListener("DOMContentLoaded",function(){
const sec=document.getElementById("officialLinksList");
if(sec){
const det=sec.closest("details");
if(det){det.addEventListener("toggle",function(){if(det.open)loadOfficialLinks()})}
else{loadOfficialLinks()}
}
});

})();

// ==== 応援通話(1対1)ページの有効化: 受け手一覧の初回ロード・履歴・誤発火ガード ====
(function(){
if(window.__koeCheeringInit)return;window.__koeCheeringInit=true;
function esc(s){try{return escapeHtml(String(s==null?"":s))}catch(e){return String(s==null?"":s).replace(/[&<>"']/g,function(c){return{"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]})}}

// loadReceivers はグローバルの .chip クリックリスナーから全チップで呼ばれ得る。
// #cheeringList を常設したことで従来の存在ガードが効かなくなるため、
// 応援通話ページが表示中のときだけ動くようにラップし直す(他ページのチップ誤作動を防止)。
if(typeof window.loadReceivers==="function"){
const _origLoadReceivers=window.loadReceivers;
window.loadReceivers=function(kind){
const pg=document.getElementById("page-cheering");
if(!pg||!pg.classList.contains("active"))return;
// loadReceivers内部は全 .chip の active をトグルするため、応援通話ページ外のチップ状態を退避→復元
const others=[];
document.querySelectorAll(".chip").forEach(function(c){if(!pg.contains(c))others.push([c,c.classList.contains("active")])});
const ret=_origLoadReceivers(kind);
others.forEach(function(pair){pair[0].classList.toggle("active",pair[1])});
return ret;
};
}

// ページ初回表示時に受け手一覧をロード
if(typeof window.showPage==="function"){
const _origShowPage=window.showPage;
window.showPage=function(name){
const r=_origShowPage(name);
if(name==="cheering"){
const l=document.getElementById("cheeringList");
if(l&&!l.querySelector(".card")&&!l.querySelector(".empty-msg")){
try{loadReceivers("recommended")}catch(e){}
}
}
return r;
};
}

async function loadCheeringHistory(){
const box=document.getElementById("cheeringHistoryList");
if(!box)return;
box.innerHTML='<div class="empty-msg">読み込み中...</div>';
let r;try{r=await callApi("get_cheering_talk_histories","1")}catch(e){r=null}
if(!r||!r.ok){box.innerHTML='<div class="empty-msg">履歴を取得できませんでした</div>';return}
const items=r.histories||[];
if(!items.length){box.innerHTML='<div class="empty-msg">通話履歴はまだありません</div>';return}
box.innerHTML=items.map(function(h){
const name=esc(h.name||h.receiver_name||h.opponent_name||("user "+(h.user_id||h.target_id||"")));
const when=esc(h.created_at||h.talked_at||h.started_at||"");
const coin=(h.coin!=null?h.coin:(h.coin_amount!=null?h.coin_amount:(h.point!=null?h.point:"")));
return '<div class="card" style="cursor:default;"><div class="card-body"><div class="card-name">'+name+'</div><div class="card-sub" style="opacity:.7;">'+when+(coin!==""?' ・ '+esc(coin)+'コイン':'')+'</div></div></div>';
}).join("");
}
async function loadCheeringSentCoins(){
const box=document.getElementById("cheeringHistoryList");
if(!box)return;
box.innerHTML='<div class="empty-msg">読み込み中...</div>';
let r;try{r=await callApi("get_cheering_sent_coins","1")}catch(e){r=null}
if(!r||!r.ok){box.innerHTML='<div class="empty-msg">取得できませんでした</div>';return}
const items=r.sent_coins||[];
if(!items.length){box.innerHTML='<div class="empty-msg">送ったコインの履歴はありません</div>';return}
box.innerHTML=items.map(function(c){
const name=esc(c.name||c.receiver_name||("user "+(c.user_id||c.target_id||"")));
const when=esc(c.created_at||c.sent_at||"");
const coin=(c.coin!=null?c.coin:(c.coin_amount!=null?c.coin_amount:(c.amount!=null?c.amount:"")));
return '<div class="card" style="cursor:default;"><div class="card-body"><div class="card-name">'+name+'</div><div class="card-sub" style="opacity:.7;">'+when+(coin!==""?' ・ '+esc(coin)+'コイン':'')+'</div></div></div>';
}).join("");
}

// 有料機能のため発信前に確認を挟む
if(typeof window.requestCheeringCall==="function"){
const _origRequestCheering=window.requestCheeringCall;
window.requestCheeringCall=async function(index){
const rcv=(window._cheeringReceivers||[])[index];
const nm=rcv&&(rcv.name||("user "+(rcv.user_id||"")))||"";
let ok=true;
try{ok=await showConfirmModal((nm?nm+"さんに":"")+"応援通話を発信しますか?(コインを消費する場合があります)")}catch(e){ok=true}
if(!ok)return;
return _origRequestCheering(index);
};
}

function bindCheeringButtons(){
const hb=document.getElementById("cheeringHistoryBtn");
if(hb&&!hb.__koeBound){hb.__koeBound=true;hb.addEventListener("click",loadCheeringHistory)}
const sb=document.getElementById("cheeringSentCoinsBtn");
if(sb&&!sb.__koeBound){sb.__koeBound=true;sb.addEventListener("click",loadCheeringSentCoins)}
}
if(document.readyState==="loading"){document.addEventListener("DOMContentLoaded",bindCheeringButtons)}else{bindCheeringButtons()}
})();

// ==== コミュニティ参加リクエスト承認UI ====
(function(){
if(window.__koeJoinReqInit)return;window.__koeJoinReqInit=true;
function esc(s){try{return escapeHtml(String(s==null?"":s))}catch(e){return String(s==null?"":s).replace(/[&<>"']/g,function(c){return{"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]})}}

async function loadCommunityJoinRequests(){
const box=document.getElementById("communityJoinRequestsList");
if(!currentCommunity||!box)return;
box.innerHTML='<div class="empty-msg">読み込み中...</div>';
let r;try{r=await callApi("get_community_join_requests",currentCommunity.id)}catch(e){r=null}
if(!r||!r.ok){box.innerHTML='<div class="empty-msg">参加申請を取得できませんでした（権限がない可能性があります）</div>';return}
const reqs=r.requests||[];
if(!reqs.length){box.innerHTML='<div class="empty-msg">保留中の参加申請はありません</div>';return}
box.innerHTML=reqs.map(function(u){
const uid=u.user_id||u.id||"";
return '<div class="card" data-uid="'+esc(uid)+'" style="align-items:center;">'
+(typeof avatarHtml==="function"?avatarHtml(u.name,u.icon_url):'')
+'<div class="card-body"><div class="card-name">'+esc(u.name||("user "+uid))+' <span class="uid-tag">ID:'+esc(uid)+'</span></div></div>'
+'<div style="display:flex;gap:6px;flex-shrink:0;">'
+'<button class="btn-primary koe-jr-approve" style="width:auto;padding:4px 12px;font-size:12px;">承認</button>'
+'<button class="btn-danger koe-jr-deny" style="width:auto;padding:4px 12px;font-size:12px;">却下</button>'
+'</div></div>';
}).join("");
box.querySelectorAll(".koe-jr-approve").forEach(function(btn){
btn.addEventListener("click",function(){handleJoinReq(btn,"approve_community_join_request","承認しました")});
});
box.querySelectorAll(".koe-jr-deny").forEach(function(btn){
btn.addEventListener("click",function(){handleJoinReq(btn,"deny_community_join_request","却下しました")});
});
}
async function handleJoinReq(btn,method,okMsg){
const card=btn.closest(".card");if(!card||!currentCommunity)return;
const uid=card.getAttribute("data-uid");if(!uid)return;
card.querySelectorAll("button").forEach(b=>b.disabled=true);
let r;try{r=await callApi(method,currentCommunity.id,String(uid))}catch(e){r=null}
if(r&&r.ok){try{toast(okMsg)}catch(e){}card.style.transition="opacity .2s";card.style.opacity="0";setTimeout(function(){card.remove();const box=document.getElementById("communityJoinRequestsList");if(box&&!box.querySelector(".card"))box.innerHTML='<div class="empty-msg">保留中の参加申請はありません</div>'},200);}
else{try{toast("操作に失敗しました","error")}catch(e){}card.querySelectorAll("button").forEach(b=>b.disabled=false);}
}

// switchCommunityTab を拡張して「参加申請」タブに対応（他タブ選択時は申請パネルを隠す）
if(typeof window.switchCommunityTab==="function"){
const _prevSwitch=window.switchCommunityTab;
window.switchCommunityTab=function(tab){
const reqBox=document.getElementById("communityJoinRequests");
if(tab==="requests"){
try{window.__koeStopCommunityRoomsAutoRefresh&&window.__koeStopCommunityRoomsAutoRefresh()}catch(e){}
document.querySelectorAll(".community-tab").forEach(function(c){c.classList.toggle("active",c.dataset.tab==="requests")});
const posts=document.getElementById("communityPosts"),members=document.getElementById("communityMembers"),inputRow=document.getElementById("communityInputRow"),rules=document.getElementById("communityRules"),rooms=document.getElementById("communityRoomsList");
if(posts)posts.style.display="none";
if(inputRow)inputRow.style.display="none";
if(members)members.style.display="none";
if(rules)rules.style.display="none";
if(rooms)rooms.style.display="none";
if(reqBox)reqBox.style.display="block";
loadCommunityJoinRequests();
return;
}
if(reqBox)reqBox.style.display="none";
return _prevSwitch(tab);
};
}
})();

// ==== マイページ新規セクション: ポイント交換/所持アイテム/装飾/購読/アンケート/バッジ/ボイスプロフィール/保存コミュニティ ====
(function(){
if(window.__koeMypageExtras)return;window.__koeMypageExtras=true;
function esc(s){try{return escapeHtml(String(s==null?"":s))}catch(e){return String(s==null?"":s).replace(/[&<>"']/g,function(c){return{"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]})}}
function pick(o){for(var i=1;i<arguments.length;i++){var k=arguments[i];if(o&&o[k]!=null&&String(o[k]).length)return o[k]}return ""}
function toastMsg(m,t){try{toast(m,t)}catch(e){}}
function simpleCard(title,sub,extra){
return '<div class="card" style="cursor:default;">'+(extra&&extra.icon?'<img loading="lazy" decoding="async" src="'+esc(extra.icon)+'" style="width:40px;height:40px;border-radius:8px;object-fit:cover;background:#333;" onerror="this.style.visibility=\'hidden\'">':'')
+'<div class="card-body"><div class="card-name">'+esc(title)+'</div>'+(sub?'<div class="card-sub" style="white-space:normal;opacity:.75;">'+esc(sub)+'</div>':'')+'</div>'+(extra&&extra.right?extra.right:'')+'</div>';
}
function lazyOnOpen(secId,fn){
const det=document.getElementById(secId);
if(!det)return;
det.addEventListener("toggle",function(){if(det.open&&!det.__koeLoaded){det.__koeLoaded=true;fn()}});
}

// --- ポイント交換 ---
let __koeEstimatedPoints=null;
async function pointEstimate(){
const inp=document.getElementById("pointExchangeInput"),out=document.getElementById("pointExchangeResult"),exec=document.getElementById("pointExecuteBtn");
if(!inp||!out)return;
const pts=(inp.value||"").trim();
if(!pts||!/^\d+$/.test(pts)){out.textContent="ポイント数を数字で入力してください";return}
out.textContent="見積もり中...";exec.disabled=true;
let r;try{r=await callApi("estimate_point_exchange",pts)}catch(e){r=null}
if(!r||!r.ok){out.textContent="見積もりに失敗しました";return}
const body=r.body||r;
__koeEstimatedPoints=pts;
out.innerHTML="見積もり結果: <b>"+esc(JSON.stringify(body).slice(0,300))+"</b><br>内容を確認して「交換を実行」を押してください。";
exec.disabled=false;
}
async function pointExecute(){
const inp=document.getElementById("pointExchangeInput"),out=document.getElementById("pointExchangeResult");
const cur=((inp.value||"").trim());
const pts=cur||__koeEstimatedPoints;
if(!pts||!/^\d+$/.test(String(pts))){toastMsg("ポイント数を数字で入力し、先に見積もりしてください","error");return}
let ok=false;try{ok=await showConfirmModal(pts+"ポイントを交換します。よろしいですか?")}catch(e){ok=false}
if(!ok)return;
out.textContent="実行中...";
let r;try{r=await callApi("execute_point_exchange",pts)}catch(e){r=null}
if(r&&r.ok){out.textContent="交換が完了しました";toastMsg("ポイントを交換しました")}
else{out.textContent="交換に失敗しました: "+esc(JSON.stringify((r&&(r.body||r.error))||"").slice(0,200));toastMsg("交換に失敗しました","error")}
}

// --- 所持アイテム ---
async function loadOwnedItems(){
const box=document.getElementById("ownedItemsList");if(!box)return;
box.innerHTML='<div class="empty-msg">読み込み中...</div>';
let r;try{r=await callApi("get_owned_items")}catch(e){r=null}
if(!r||!r.ok){box.innerHTML='<div class="empty-msg">取得できませんでした</div>';return}
const items=r.items||[];
if(!items.length){box.innerHTML='<div class="empty-msg">所持アイテムはありません</div>';return}
box.innerHTML=items.map(function(it){
return simpleCard(pick(it,"name","item_name","title")||"アイテム", (it.count!=null?"×"+it.count:pick(it,"description")), {icon:pick(it,"icon_url","image_url","item_image_file_path")});
}).join("");
}

// --- 装飾アイテム ---
async function loadDecorationItems(){
const box=document.getElementById("decorationItemsList");if(!box)return;
box.innerHTML='<div class="empty-msg">読み込み中...</div>';
let r;try{r=await callApi("get_decoration_items")}catch(e){r=null}
if(!r||!r.ok){box.innerHTML='<div class="empty-msg">取得できませんでした</div>';return}
const items=r.items||[];
if(!items.length){box.innerHTML='<div class="empty-msg">装飾アイテムはありません</div>';return}
box.innerHTML=items.map(function(it){
const id=pick(it,"id","decoration_item_id","item_id");
const price=pick(it,"price","coin","coin_amount","point");
const right='<button class="btn-secondary koe-buy-deco" data-id="'+esc(id)+'" style="width:auto;padding:4px 12px;font-size:12px;flex-shrink:0;">'+(price!==""?esc(price)+'で購入':'購入')+'</button>';
return simpleCard(pick(it,"name","item_name","title")||"装飾", pick(it,"description"), {icon:pick(it,"icon_url","image_url","item_image_file_path"),right:right});
}).join("");
box.querySelectorAll(".koe-buy-deco").forEach(function(btn){
btn.addEventListener("click",async function(){
const id=btn.getAttribute("data-id");if(!id)return;
let ok=false;try{ok=await showConfirmModal("この装飾アイテムを購入しますか?(コインを消費します)")}catch(e){ok=false}
if(!ok)return;
btn.disabled=true;
let r;try{r=await callApi("purchase_decoration_item",String(id))}catch(e){r=null}
if(r&&r.ok){toastMsg("購入しました");btn.textContent="購入済み"}
else{toastMsg("購入に失敗しました","error");btn.disabled=false}
});
});
}

// --- 購読 ---
async function loadSubscriptions(which){
const box=document.getElementById("subscriptionsList");if(!box)return;
box.innerHTML='<div class="empty-msg">読み込み中...</div>';
const method=which==="history"?"get_subscription_histories":"get_subscriptions";
let r;try{r=await callApi(method)}catch(e){r=null}
if(!r||!r.ok){box.innerHTML='<div class="empty-msg">取得できませんでした</div>';return}
const items=(which==="history"?r.subscription_histories:r.subscriptions)||[];
if(!items.length){box.innerHTML='<div class="empty-msg">'+(which==="history"?"購読履歴はありません":"現在有効な購読はありません")+'</div>';return}
box.innerHTML=items.map(function(s){
return simpleCard(pick(s,"name","plan_name","product_name","title")||"プラン", pick(s,"status","expires_at","period","started_at","created_at"));
}).join("");
}

// --- アンケート ---
async function loadEnquetes(){
const box=document.getElementById("enquetesList");if(!box)return;
box.innerHTML='<div class="empty-msg">読み込み中...</div>';
let r;try{r=await callApi("get_enquetes")}catch(e){r=null}
if(!r||!r.ok){box.innerHTML='<div class="empty-msg">取得できませんでした</div>';return}
const items=r.enquetes||[];
if(!items.length){box.innerHTML='<div class="empty-msg">回答できるアンケートはありません</div>';return}
box.innerHTML=items.map(function(e){
const id=pick(e,"id","enquete_id");
const right='<button class="btn-secondary koe-enq-open" data-id="'+esc(id)+'" style="width:auto;padding:4px 12px;font-size:12px;flex-shrink:0;">回答する</button>';
return '<div class="card" style="cursor:default;" data-enq="'+esc(id)+'"><div class="card-body"><div class="card-name">'+esc(pick(e,"title","name")||"アンケート")+'</div>'+((pick(e,"description"))?'<div class="card-sub" style="white-space:normal;opacity:.75;">'+esc(pick(e,"description"))+'</div>':'')+'<div class="koe-enq-form"></div></div>'+right+'</div>';
}).join("");
box.querySelectorAll(".koe-enq-open").forEach(function(btn){
btn.addEventListener("click",function(){openEnquete(btn.getAttribute("data-id"),btn.closest(".card"))});
});
}
async function openEnquete(enqueteId,card){
if(!card)return;
const form=card.querySelector(".koe-enq-form");if(!form)return;
if(form.__open){form.innerHTML="";form.__open=false;return}
form.__open=true;
form.innerHTML='<div class="empty-msg" style="text-align:left;">設問を読み込み中...</div>';
let r;try{r=await callApi("get_enquete_questions",String(enqueteId))}catch(e){r=null}
if(!r||!r.ok){form.innerHTML='<div class="empty-msg" style="text-align:left;">設問を取得できませんでした</div>';return}
const qs=r.questions||[];
if(!qs.length){form.innerHTML='<div class="empty-msg" style="text-align:left;">設問がありません</div>';return}
form.innerHTML=qs.map(function(q,i){
const qid=pick(q,"id","question_id")||String(i);
return '<div style="margin-top:8px;" data-qid="'+esc(qid)+'"><div style="font-size:13px;margin-bottom:4px;">'+esc(pick(q,"text","title","question","body")||("設問"+(i+1)))+'</div><input type="text" class="koe-enq-ans" placeholder="回答を入力" style="width:100%;"></div>';
}).join("")+'<button class="btn-primary koe-enq-submit" style="width:auto;margin-top:8px;">送信</button>';
form.querySelector(".koe-enq-submit").addEventListener("click",async function(){
const rows=form.querySelectorAll("[data-qid]");
let sent=0,failed=0;
for(let i=0;i<rows.length;i++){
const qid=rows[i].getAttribute("data-qid");
const ans=(rows[i].querySelector(".koe-enq-ans")||{}).value||"";
if(!ans)continue;
let rr;try{rr=await callApi("answer_enquete",String(enqueteId),String(qid),String(ans))}catch(e){rr=null}
if(rr&&rr.ok)sent++;else failed++;
}
toastMsg(failed?("送信 "+sent+"件 / 失敗 "+failed+"件"):(sent?"回答を送信しました":"回答が空です"),failed?"error":undefined);
});
}

// --- 表示バッジ ---
async function loadDisplayBadges(){
const box=document.getElementById("displayBadgesList");if(!box)return;
box.innerHTML='<div class="empty-msg">読み込み中...</div>';
let r;try{r=await callApi("get_badges",null)}catch(e){r=null}
if(!r||!r.ok){box.innerHTML='<div class="empty-msg">取得できませんでした</div>';return}
const items=r.badges||[];
if(!items.length){box.innerHTML='<div class="empty-msg">獲得したバッジはありません</div>';return}
box.innerHTML=items.map(function(b){
const id=pick(b,"id","badge_id");
const right='<button class="btn-secondary koe-set-badge" data-id="'+esc(id)+'" style="width:auto;padding:4px 12px;font-size:12px;flex-shrink:0;">表示に設定</button>';
return simpleCard(pick(b,"name","description")||"バッジ","",{icon:pick(b,"icon_url","image_url"),right:right});
}).join("");
box.querySelectorAll(".koe-set-badge").forEach(function(btn){
btn.addEventListener("click",async function(){
const id=btn.getAttribute("data-id");
btn.disabled=true;
let r;try{r=await callApi("set_display_badge",id?String(id):"")}catch(e){r=null}
if(r&&r.ok){toastMsg("表示バッジを設定しました");box.querySelectorAll(".koe-set-badge").forEach(b=>{b.textContent="表示に設定";b.disabled=false});btn.textContent="設定中";btn.disabled=true}
else{toastMsg("設定に失敗しました","error");btn.disabled=false}
});
});
}

// --- ボイスプロフィール ---
async function loadVoiceProfiles(){
const box=document.getElementById("voiceProfilesList");if(!box)return;
box.innerHTML='<div class="empty-msg">読み込み中...</div>';
let r;try{r=await callApi("get_voice_profiles")}catch(e){r=null}
if(!r||!r.ok){box.innerHTML='<div class="empty-msg">取得できませんでした</div>';return}
const items=r.voice_profiles||[];
if(!items.length){box.innerHTML='<div class="empty-msg">ボイスプロフィールはありません</div>';return}
box.innerHTML=items.map(function(v){
const url=pick(v,"voice_url","voice_file_path","url","file_path");
const right=url?'<button class="btn-secondary koe-play-voice" data-url="'+esc(url)+'" style="width:auto;padding:4px 12px;font-size:12px;flex-shrink:0;">再生</button>':'';
return simpleCard(pick(v,"title","name","label")||"ボイス", pick(v,"description","created_at"), {right:right});
}).join("");
box.querySelectorAll(".koe-play-voice").forEach(function(btn){
btn.addEventListener("click",function(){
let u=btn.getAttribute("data-url");if(!u)return;
if(!/^https?:/.test(u)&&typeof voiceUrl==="function"){try{u=voiceUrl(u)}catch(e){}}
try{const a=new Audio(u);a.play()}catch(e){toastMsg("再生できませんでした","error")}
});
});
}

// --- 保存したコミュニティ ---
async function loadCommunityBookmarks(){
const box=document.getElementById("communityBookmarksList");if(!box)return;
box.innerHTML='<div class="empty-msg">読み込み中...</div>';
let r;try{r=await callApi("get_community_bookmarks","1")}catch(e){r=null}
if(!r||!r.ok){box.innerHTML='<div class="empty-msg">取得できませんでした</div>';return}
const items=r.communities||[];
if(!items.length){box.innerHTML='<div class="empty-msg">保存したコミュニティはありません</div>';return}
box.innerHTML=items.map(function(c){
return '<div class="card koe-open-comm" data-id="'+esc(c.id)+'" data-name="'+esc(c.name||"")+'" style="cursor:pointer;">'
+(pick(c,"icon_url")?'<img loading="lazy" decoding="async" src="'+esc(c.icon_url)+'" style="width:40px;height:40px;border-radius:8px;object-fit:cover;background:#333;" onerror="this.style.visibility=\'hidden\'">':'')
+'<div class="card-body"><div class="card-name">'+esc(c.name||"コミュニティ")+'</div>'+(c.description?'<div class="card-sub" style="white-space:normal;opacity:.75;">'+esc(c.description)+'</div>':'')+'</div></div>';
}).join("");
box.querySelectorAll(".koe-open-comm").forEach(function(card){
card.addEventListener("click",function(){
const id=card.getAttribute("data-id"),name=card.getAttribute("data-name");
if(id&&typeof openCommunity==="function")openCommunity(id,name,true);
});
});
}

// バインド
function initMypageExtras(){
const pe=document.getElementById("pointEstimateBtn");if(pe)pe.addEventListener("click",pointEstimate);
const px=document.getElementById("pointExecuteBtn");if(px)px.addEventListener("click",pointExecute);
const sc=document.getElementById("subsCurrentBtn");if(sc)sc.addEventListener("click",function(){loadSubscriptions("current")});
const sh=document.getElementById("subsHistoryBtn");if(sh)sh.addEventListener("click",function(){loadSubscriptions("history")});
lazyOnOpen("secOwnedItems",loadOwnedItems);
lazyOnOpen("secDecoration",loadDecorationItems);
lazyOnOpen("secSubscriptions",function(){loadSubscriptions("current")});
lazyOnOpen("secEnquete",loadEnquetes);
lazyOnOpen("secBadges",loadDisplayBadges);
lazyOnOpen("secVoiceProfiles",loadVoiceProfiles);
lazyOnOpen("secCommunityBookmarks",loadCommunityBookmarks);
}
if(document.readyState==="loading"){document.addEventListener("DOMContentLoaded",initMypageExtras)}else{initMypageExtras()}
})();

// ==== 投げ銭 (do_tipping + item_packs) ====
(function(){
if(window.__koeTipInit)return;window.__koeTipInit=true;
function esc(s){try{return escapeHtml(String(s==null?"":s))}catch(e){return String(s==null?"":s).replace(/[&<>"']/g,function(c){return{"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]})}}
let tipTargetUserId=null;
let __tipPacks=null;

async function openTipModal(){
if(typeof profileViewFollowState==="undefined"||!profileViewFollowState||!profileViewFollowState.userId){try{toast("対象ユーザーが不明です","error")}catch(e){}return}
tipTargetUserId=profileViewFollowState.userId;
const nameEl=document.getElementById("tipTargetName");
const pn=document.getElementById("profileViewName");
if(nameEl)nameEl.textContent=pn?("→ "+pn.textContent):"";
const modal=document.getElementById("tipModal");if(modal)modal.style.display="flex";
const box=document.getElementById("tipItemList");
if(!box)return;
box.innerHTML='<div class="empty-msg">読み込み中...</div>';
let r;try{r=await callApi("get_item_packs")}catch(e){r=null}
if(!r||!r.ok){box.innerHTML='<div class="empty-msg">アイテムを取得できませんでした</div>';return}
__tipPacks=r.items||[];
if(!__tipPacks.length){box.innerHTML='<div class="empty-msg">送れるアイテムがありません</div>';return}
box.innerHTML=__tipPacks.map(function(it,i){
return '<div class="card koe-tip-item" data-idx="'+i+'" style="cursor:pointer;">'
+(it.icon_url?'<img loading="lazy" decoding="async" src="'+esc(it.icon_url)+'" style="width:44px;height:44px;border-radius:8px;object-fit:cover;background:#333;" onerror="this.style.visibility=\'hidden\'">':'')
+'<div class="card-body"><div class="card-name">'+esc(it.name||"アイテム")+'</div><div class="card-sub" style="opacity:.75;">'+esc(it.coin!=null?it.coin+"コイン":"")+'</div></div>'
+'<span style="font-size:18px;opacity:.6;">▶</span></div>';
}).join("");
box.querySelectorAll(".koe-tip-item").forEach(function(card){
card.addEventListener("click",function(){sendTip(parseInt(card.getAttribute("data-idx"),10),card)});
});
}
async function sendTip(idx,card){
const pack=(__tipPacks||[])[idx];
if(!pack||tipTargetUserId==null)return;
let ok=false;try{ok=await showConfirmModal("「"+(pack.name||"アイテム")+"」("+(pack.coin!=null?pack.coin+"コイン":"")+")を投げ銭しますか?")}catch(e){ok=false}
if(!ok)return;
card.style.pointerEvents="none";card.style.opacity="0.6";
let r;try{r=await callApi("do_tipping",String(pack.id),String(tipTargetUserId),"0")}catch(e){r=null}
if(r&&r.ok){try{toast("投げ銭しました")}catch(e){}const m=document.getElementById("tipModal");if(m)m.style.display="none"}
else{try{toast("投げ銭に失敗しました: "+JSON.stringify((r&&(r.body||r.error))||"").slice(0,120),"error")}catch(e){}card.style.pointerEvents="";card.style.opacity=""}
}

function initTip(){
const tb=document.getElementById("profileViewTipBtn");
if(tb)tb.addEventListener("click",openTipModal);
const tc=document.getElementById("tipModalClose");
if(tc)tc.addEventListener("click",function(){const m=document.getElementById("tipModal");if(m)m.style.display="none"});
const modal=document.getElementById("tipModal");
if(modal)modal.addEventListener("click",function(e){if(e.target===modal)modal.style.display="none"});
}
if(document.readyState==="loading"){document.addEventListener("DOMContentLoaded",initTip)}else{initTip()}
})();

// ==== 応援トーク 受け手コンソール ====
(function(){
if(window.__koeReceiverConsole)return;window.__koeReceiverConsole=true;
function esc(s){try{return escapeHtml(String(s==null?"":s))}catch(e){return String(s==null?"":s).replace(/[&<>"']/g,function(c){return{"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]})}}
function toastMsg(m,t){try{toast(m,t)}catch(e){}}
function receiverId(){
const v=(document.getElementById("receiverIdInput")||{}).value;
if(v&&v.trim())return v.trim();
try{if(typeof myUserId!=="undefined"&&myUserId)return String(myUserId)}catch(e){}
return "";
}
function renderList(items,mapper,emptyMsg){
const box=document.getElementById("receiverConsoleList");if(!box)return;
if(!items||!items.length){box.innerHTML='<div class="empty-msg">'+esc(emptyMsg||"データがありません")+'</div>';return}
box.innerHTML=items.map(mapper).join("");
}
function genericItems(r,keys){
for(let i=0;i<keys.length;i++){if(r&&Array.isArray(r[keys[i]]))return r[keys[i]]}
return [];
}

async function setReceiverStatus(status){
const rid=receiverId();if(!rid){toastMsg("受け手IDが不明です","error");return}
let r;try{r=await callApi("update_cheering_receiver_status",rid,status)}catch(e){r=null}
toastMsg(r&&r.ok?("受付を"+(status==="active"?"開始":"停止")+"しました"):"変更に失敗しました",r&&r.ok?undefined:"error");
}
async function loadReceives(){
const box=document.getElementById("receiverConsoleList");if(box)box.innerHTML='<div class="empty-msg">読み込み中...</div>';
let r;try{r=await callApi("get_cheering_request_receives")}catch(e){r=null}
if(!r||!r.ok){renderList([],null,"取得できませんでした");return}
const items=genericItems(r,["requests","data","receives"]);
renderList(items,function(it){
const uid=it.user_id||it.from_user_id||it.id||"";
const nm=it.name||("user "+uid);
return '<div class="card" style="align-items:center;"><div class="card-body"><div class="card-name">'+esc(nm)+' <span class="uid-tag">ID:'+esc(uid)+'</span></div><div class="card-sub" style="opacity:.7;">'+esc(it.status||it.created_at||"")+'</div></div>'
+'<div style="display:flex;gap:6px;flex-shrink:0;"><button class="btn-secondary koe-rc-coin" data-uid="'+esc(uid)+'" style="width:auto;padding:4px 10px;font-size:12px;">コイン送る</button><button class="btn-secondary koe-rc-rate" data-uid="'+esc(uid)+'" style="width:auto;padding:4px 10px;font-size:12px;">評価</button></div></div>';
},"着信リクエストはありません");
bindRowActions();
}
async function loadStandby(){
const rid=receiverId();
const box=document.getElementById("receiverConsoleList");if(box)box.innerHTML='<div class="empty-msg">読み込み中...</div>';
let r;try{r=await callApi("get_cheering_standby_requests",rid)}catch(e){r=null}
if(!r||!r.ok){renderList([],null,"取得できませんでした");return}
renderList(genericItems(r,["requests","data","standby_requests"]),function(it){
const uid=it.user_id||it.id||"";
return '<div class="card" style="cursor:default;"><div class="card-body"><div class="card-name">'+esc(it.name||("user "+uid))+'</div><div class="card-sub" style="opacity:.7;">'+esc(it.status||it.created_at||"")+'</div></div></div>';
},"待機リクエストはありません");
}
async function loadCoins(){
const rid=receiverId();
const box=document.getElementById("receiverConsoleList");if(box)box.innerHTML='<div class="empty-msg">読み込み中...</div>';
let r;try{r=await callApi("get_cheering_receiver_coin_list",rid)}catch(e){r=null}
if(!r||!r.ok){renderList([],null,"取得できませんでした");return}
renderList(genericItems(r,["coins","data","coin_list"]),function(it){
return '<div class="card" style="cursor:default;"><div class="card-body"><div class="card-name">'+esc((it.coin!=null?it.coin+"コイン":(it.amount!=null?it.amount+"コイン":"コイン")))+'</div><div class="card-sub" style="opacity:.7;">'+esc(it.name||it.from_user_name||it.created_at||"")+'</div></div></div>';
},"受取コインはありません");
}
async function loadDetail(){
const rid=receiverId();
const box=document.getElementById("receiverConsoleList");if(box)box.innerHTML='<div class="empty-msg">読み込み中...</div>';
let r;try{r=await callApi("get_cheering_receiver_detail",rid)}catch(e){r=null}
if(!r||!r.ok){renderList([],null,"取得できませんでした");return}
const d=r.detail||r;
if(box)box.innerHTML='<div class="card" style="cursor:default;"><div class="card-body"><div class="card-sub" style="white-space:pre-wrap;opacity:.85;">'+esc(JSON.stringify(d,null,2).slice(0,800))+'</div></div></div>';
}
function bindRowActions(){
const box=document.getElementById("receiverConsoleList");if(!box)return;
box.querySelectorAll(".koe-rc-coin").forEach(function(btn){btn.addEventListener("click",async function(){
const uid=btn.getAttribute("data-uid");if(!uid)return;
let amt="";try{amt=await showInputModal?await showInputModal("送るコイン数を入力"):prompt("送るコイン数")}catch(e){amt=prompt("送るコイン数")}
if(!amt||!/^\d+$/.test(String(amt).trim()))return;
let r;try{r=await callApi("cheering_send_coins",String(uid),String(amt).trim())}catch(e){r=null}
toastMsg(r&&r.ok?"コインを送りました":"送信に失敗しました",r&&r.ok?undefined:"error");
})});
box.querySelectorAll(".koe-rc-rate").forEach(function(btn){btn.addEventListener("click",async function(){
const uid=btn.getAttribute("data-uid");if(!uid)return;
let rating="";try{rating=prompt("評価(例: 5)")}catch(e){}
if(!rating||!/^\d+$/.test(String(rating).trim()))return;
let r;try{r=await callApi("rate_cheering_call",String(uid),String(rating).trim(),"")}catch(e){r=null}
toastMsg(r&&r.ok?"評価しました":"評価に失敗しました",r&&r.ok?undefined:"error");
})});
}
function initReceiverConsole(){
const on=document.getElementById("receiverStatusOnBtn");if(on)on.addEventListener("click",function(){setReceiverStatus("active")});
const off=document.getElementById("receiverStatusOffBtn");if(off)off.addEventListener("click",function(){setReceiverStatus("inactive")});
const rr=document.getElementById("receiverReceivesBtn");if(rr)rr.addEventListener("click",loadReceives);
const rs=document.getElementById("receiverStandbyBtn");if(rs)rs.addEventListener("click",loadStandby);
const rc=document.getElementById("receiverCoinsBtn");if(rc)rc.addEventListener("click",loadCoins);
const rd=document.getElementById("receiverDetailBtn");if(rd)rd.addEventListener("click",loadDetail);
}
if(document.readyState==="loading"){document.addEventListener("DOMContentLoaded",initReceiverConsole)}else{initReceiverConsole()}
})();

// ==== キャンペーン / アイテムパック / 枠設定 / お試し視聴 / 録音同意 (マイページ) ====
(function(){
if(window.__koeMypageExtras2)return;window.__koeMypageExtras2=true;
function esc(s){try{return escapeHtml(String(s==null?"":s))}catch(e){return String(s==null?"":s).replace(/[&<>"']/g,function(c){return{"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]})}}
function pick(o){for(var i=1;i<arguments.length;i++){var k=arguments[i];if(o&&o[k]!=null&&String(o[k]).length)return o[k]}return ""}
function toastMsg(m,t){try{toast(m,t)}catch(e){}}
function lazyOnOpen(secId,fn){const det=document.getElementById(secId);if(!det)return;det.addEventListener("toggle",function(){if(det.open&&!det.__koeLoaded){det.__koeLoaded=true;fn()}});}

// --- キャンペーン ---
async function loadCampaigns(){
const box=document.getElementById("campaignsList");if(!box)return;
box.innerHTML='<div class="empty-msg">読み込み中...</div>';
let r;try{r=await callApi("get_user_campaigns")}catch(e){r=null}
if(!r||!r.ok){box.innerHTML='<div class="empty-msg">取得できませんでした</div>';return}
const items=r.user_campaigns||r.campaigns||[];
if(!items.length){box.innerHTML='<div class="empty-msg">参加できるキャンペーンはありません</div>';return}
box.innerHTML=items.map(function(c){
const id=pick(c,"id","campaign_id","user_campaign_id");
return '<div class="card" style="cursor:default;" data-id="'+esc(id)+'"><div class="card-body"><div class="card-name">'+esc(pick(c,"title","name")||"キャンペーン")+'</div>'
+((pick(c,"description"))?'<div class="card-sub" style="white-space:normal;opacity:.75;">'+esc(pick(c,"description"))+'</div>':'')
+'<div class="card-sub" style="opacity:.7;">'+esc(pick(c,"status","period","expires_at",""))+'</div></div>'
+'<div style="display:flex;gap:6px;flex-shrink:0;flex-direction:column;">'
+'<button class="btn-secondary koe-camp-join" data-id="'+esc(id)+'" style="width:auto;padding:4px 10px;font-size:12px;">参加</button>'
+'<button class="btn-secondary koe-camp-read" data-id="'+esc(id)+'" style="width:auto;padding:4px 10px;font-size:12px;">既読</button>'
+'<button class="btn-secondary koe-camp-prog" data-id="'+esc(id)+'" style="width:auto;padding:4px 10px;font-size:12px;">進捗</button>'
+'<button class="btn-secondary koe-camp-recover" data-id="'+esc(id)+'" style="width:auto;padding:4px 10px;font-size:12px;">リカバリ</button>'
+'</div></div>';
}).join("");
box.querySelectorAll(".koe-camp-join").forEach(function(b){b.addEventListener("click",async function(){
const id=b.getAttribute("data-id");b.disabled=true;
let r;try{r=await callApi("join_campaign",String(id))}catch(e){r=null}
toastMsg(r&&r.ok?"参加しました":"参加に失敗しました",r&&r.ok?undefined:"error");b.disabled=false;
})});
box.querySelectorAll(".koe-camp-read").forEach(function(b){b.addEventListener("click",async function(){
const id=b.getAttribute("data-id");b.disabled=true;
let r;try{r=await callApi("mark_user_campaign_as_read",String(id))}catch(e){r=null}
toastMsg(r&&r.ok?"既読にしました":"失敗しました",r&&r.ok?undefined:"error");b.disabled=false;
})});
box.querySelectorAll(".koe-camp-prog").forEach(function(b){b.addEventListener("click",async function(){
const id=b.getAttribute("data-id");
let chid="";try{chid=prompt("チャレンジID(challenge_id)を入力")}catch(e){}
if(!chid)return;
const card=b.closest(".card");let box2=card?card.querySelector(".koe-camp-prog-res"):null;
if(card&&!box2){box2=document.createElement("div");box2.className="card-sub koe-camp-prog-res";box2.style.cssText="white-space:pre-wrap;opacity:.85;margin-top:6px;";card.querySelector(".card-body").appendChild(box2)}
if(box2)box2.textContent="読み込み中...";
let r;try{r=await callApi("get_campaign_challenge_progress",String(id),String(chid))}catch(e){r=null}
if(box2)box2.textContent=(r&&r.ok)?JSON.stringify(r.progress||r,null,2).slice(0,500):"取得できませんでした";
})});
box.querySelectorAll(".koe-camp-recover").forEach(function(b){b.addEventListener("click",async function(){
const id=b.getAttribute("data-id");b.disabled=true;
let r;try{r=await callApi("recover_user_campaign",String(id))}catch(e){r=null}
toastMsg(r&&r.ok?"進捗をリカバリしました":"失敗しました",r&&r.ok?undefined:"error");b.disabled=false;
})});
}

// --- アイテムパック ---
async function loadItemPacks(){
const box=document.getElementById("itemPacksList");if(!box)return;
box.innerHTML='<div class="empty-msg">読み込み中...</div>';
let r;try{r=await callApi("get_item_packs")}catch(e){r=null}
if(!r||!r.ok){box.innerHTML='<div class="empty-msg">取得できませんでした</div>';return}
const items=r.items||[];
if(!items.length){box.innerHTML='<div class="empty-msg">アイテムはありません</div>';return}
box.innerHTML=items.map(function(it){
return '<div class="card" style="cursor:default;">'+(it.icon_url?'<img loading="lazy" decoding="async" src="'+esc(it.icon_url)+'" style="width:40px;height:40px;border-radius:8px;object-fit:cover;background:#333;" onerror="this.style.visibility=\'hidden\'">':'')
+'<div class="card-body"><div class="card-name">'+esc(it.name||"アイテム")+'</div><div class="card-sub" style="opacity:.7;">'+esc(it.coin!=null?it.coin+"コイン":"")+'</div></div></div>';
}).join("");
}

// --- 枠のデフォルト設定 ---
async function getRoomDefaults(){try{return JSON.parse(localStorage.getItem("koe_room_defaults")||"{}")}catch(e){return{}}}
function saveRoomDefaults(d){try{localStorage.setItem("koe_room_defaults",JSON.stringify(d))}catch(e){}}
async function loadRoomSettings(){
const box=document.getElementById("roomSettingsBox");if(!box)return;
const d=getRoomDefaults();
box.innerHTML='<div class="card" style="cursor:default;"><div class="card-body" style="display:flex;flex-direction:column;gap:10px;">'
+'<div class="field-label">デフォルトの枠名</div>'
+'<input id="roomDefTitle" type="text" placeholder="枠名（新規作成時の初期値）" style="width:100%;padding:9px;border-radius:8px;background:var(--bg-input,#1c1c1c);color:var(--text-normal,#dbdee1);border:1px solid var(--border);box-sizing:border-box;">'
+'<label class="check-row"><input type="checkbox" id="roomDefPublic"> デフォルトで公開する</label>'
+'<label class="check-row"><input type="checkbox" id="roomDefComment"> デフォルトでコメントを許可</label>'
+'<button id="roomDefSave" class="btn-primary" style="width:auto;align-self:flex-start;">この設定を保存</button>'
+'<div class="card-sub" style="opacity:.6;font-size:11px;">保存すると「新しく通話を開く」時にこの値が初期表示されます。</div>'
+'</div></div>';
var ti=document.getElementById("roomDefTitle");if(ti)ti.value=d.title||"";
var pu=document.getElementById("roomDefPublic");if(pu)pu.checked=d.public!==false;
var co=document.getElementById("roomDefComment");if(co)co.checked=d.comment!==false;
var sv=document.getElementById("roomDefSave");
if(sv)sv.addEventListener("click",function(){saveRoomDefaults({title:(ti&&ti.value||"").trim(),public:!!(pu&&pu.checked),comment:!!(co&&co.checked)});if(typeof toast==="function")toast("枠のデフォルト設定を保存しました");if(typeof haptic==="function")haptic(10);});
}

// --- お試し視聴 ---
async function loadTrialListenings(){
const box=document.getElementById("trialListeningsList");if(!box)return;
box.innerHTML='<div class="empty-msg">読み込み中...</div>';
let r;try{r=await callApi("get_trial_listenings")}catch(e){r=null}
if(!r||!r.ok){box.innerHTML='<div class="empty-msg">取得できませんでした</div>';return}
const items=r.trial_listenings||[];
if(!items.length){box.innerHTML='<div class="empty-msg">お試し視聴はありません</div>';return}
box.innerHTML=items.map(function(t){
return '<div class="card" style="cursor:default;"><div class="card-body"><div class="card-name">'+esc(pick(t,"title","name","description")||"お試し視聴")+'</div><div class="card-sub" style="opacity:.7;">'+esc(pick(t,"owner_name","created_at",""))+'</div></div></div>';
}).join("");
}

// --- 録音同意 ---
async function loadRecAgreements(){
const box=document.getElementById("recordingBox");if(!box)return;
box.innerHTML='<div class="empty-msg">読み込み中...</div>';
let r;try{r=await callApi("get_talk_recording_agreements")}catch(e){r=null}
if(!r||!r.ok){box.innerHTML='<div class="empty-msg">取得できませんでした</div>';return}
const d=r.data||r;
box.innerHTML='<div class="card" style="cursor:default;"><div class="card-body"><div class="card-sub" style="white-space:pre-wrap;opacity:.85;">'+esc(JSON.stringify(d,null,2).slice(0,800))+'</div></div></div>';
}
async function agreeRec(){
let ok=false;try{ok=await showConfirmModal("通話録音に同意しますか?")}catch(e){ok=false}
if(!ok)return;
let r;try{r=await callApi("agree_talk_recording","")}catch(e){r=null}
toastMsg(r&&r.ok?"同意しました":"失敗しました",r&&r.ok?undefined:"error");
}
async function checkRecDisabled(){
const box=document.getElementById("recordingBox");
const uids=((document.getElementById("recCheckUidsInput")||{}).value||"").trim();
if(!uids){toastMsg("user_idを入力してください","error");return}
if(box)box.innerHTML='<div class="empty-msg">確認中...</div>';
let r;try{r=await callApi("check_recording_disabled_users",uids)}catch(e){r=null}
if(!r||!r.ok){if(box)box.innerHTML='<div class="empty-msg">確認できませんでした</div>';return}
const d=r.body||r;
if(box)box.innerHTML='<div class="card" style="cursor:default;"><div class="card-body"><div class="card-sub" style="white-space:pre-wrap;opacity:.85;">'+esc(JSON.stringify(d,null,2).slice(0,800))+'</div></div></div>';
}

function init2(){
lazyOnOpen("secCampaigns",loadCampaigns);
lazyOnOpen("secItemPacks",loadItemPacks);
lazyOnOpen("secRoomSettings",loadRoomSettings);
lazyOnOpen("secTrialListenings",loadTrialListenings);
const ab=document.getElementById("recAgreementsBtn");if(ab)ab.addEventListener("click",loadRecAgreements);
const gb=document.getElementById("recAgreeBtn");if(gb)gb.addEventListener("click",agreeRec);
const cb=document.getElementById("recCheckBtn");if(cb)cb.addEventListener("click",checkRecDisabled);
}
if(document.readyState==="loading"){document.addEventListener("DOMContentLoaded",init2)}else{init2()}
})();

// ==== コミュニティ 通報 + 管理ツール(コメント操作/役割変更/コメント表示/申請取消) ====
(function(){
if(window.__koeCommToolsInit)return;window.__koeCommToolsInit=true;
function esc(s){try{return escapeHtml(String(s==null?"":s))}catch(e){return String(s==null?"":s).replace(/[&<>"']/g,function(c){return{"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]})}}
function toastMsg(m,t){try{toast(m,t)}catch(e){}}
function cid(){return (typeof currentCommunity!=="undefined"&&currentCommunity)?currentCommunity.id:null}
function val(id){return ((document.getElementById(id)||{}).value||"").trim()}
function resBox(){return document.getElementById("communityToolsResult")}

async function reportCommunity(){
const id=cid();if(!id){toastMsg("コミュニティが不明です","error");return}
let reason="";try{reason=prompt("通報理由を入力してください")}catch(e){}
if(reason==null)return;
let r;try{r=await callApi("report_community",String(id),String(reason||""))}catch(e){r=null}
toastMsg(r&&r.ok?"通報しました":"通報に失敗しました",r&&r.ok?undefined:"error");
}
async function commentLike(unlike){
const id=cid(),pid=val("ctPostId"),cmid=val("ctCommentId");
if(!id||!pid||!cmid){toastMsg("post_id と comment_id を入力してください","error");return}
let r;try{r=await callApi("toggle_community_comment_like",String(id),String(pid),String(cmid),!!unlike)}catch(e){r=null}
toastMsg(r&&r.ok?(unlike?"いいねを解除しました":"いいねしました"):"失敗しました",r&&r.ok?undefined:"error");
}
async function commentDelete(){
const id=cid(),pid=val("ctPostId"),cmid=val("ctCommentId");
if(!id||!pid||!cmid){toastMsg("post_id と comment_id を入力してください","error");return}
let ok=false;try{ok=await showConfirmModal("このコメントを削除しますか?")}catch(e){ok=false}
if(!ok)return;
let r;try{r=await callApi("delete_community_comment",String(id),String(pid),String(cmid))}catch(e){r=null}
toastMsg(r&&r.ok?"削除しました":"削除に失敗しました",r&&r.ok?undefined:"error");
}
async function changeRole(){
const id=cid(),rid=val("ctRoomId"),tid=val("ctTargetId"),role=val("ctRole")||"listener";
if(!id||!rid||!tid){toastMsg("room_id と target_id を入力してください","error");return}
let r;try{r=await callApi("change_community_talk_room_role",String(id),String(rid),String(tid),role)}catch(e){r=null}
toastMsg(r&&r.ok?"役割を変更しました":"変更に失敗しました",r&&r.ok?undefined:"error");
}
async function showRoomComments(){
const id=cid(),rid=val("ctRoomCommentsId");
if(!id||!rid){toastMsg("room_id を入力してください","error");return}
const box=resBox();if(box)box.innerHTML='<div class="empty-msg">読み込み中...</div>';
let r;try{r=await callApi("get_community_talk_room_comments",String(id),String(rid))}catch(e){r=null}
if(!r||!r.ok){if(box)box.innerHTML='<div class="empty-msg">取得できませんでした</div>';return}
const items=r.comments||[];
if(!items.length){if(box)box.innerHTML='<div class="empty-msg">コメントはありません</div>';return}
if(box)box.innerHTML=items.map(function(c){
return '<div class="card" style="cursor:default;">'+(c.icon_url?'<img loading="lazy" decoding="async" src="'+esc(c.icon_url)+'" style="width:34px;height:34px;border-radius:50%;object-fit:cover;background:#333;" onerror="this.style.visibility=\'hidden\'">':'')+'<div class="card-body"><div class="card-name">'+esc(c.name||("user "+(c.user_id||"")))+'</div><div class="card-sub" style="white-space:normal;opacity:.8;">'+esc(c.text||"")+'</div></div></div>';
}).join("");
}
async function cancelJoin(){
const id=cid();if(!id){toastMsg("コミュニティが不明です","error");return}
let ok=false;try{ok=await showConfirmModal("このコミュニティへの参加申請を取り消しますか?")}catch(e){ok=false}
if(!ok)return;
let r;try{r=await callApi("cancel_community_join_request",String(id))}catch(e){r=null}
toastMsg(r&&r.ok?"申請を取り消しました":"取り消しに失敗しました",r&&r.ok?undefined:"error");
}

function initCommTools(){
const rep=document.getElementById("communityReportBtn");if(rep)rep.addEventListener("click",reportCommunity);
const cl=document.getElementById("ctCommentLikeBtn");if(cl)cl.addEventListener("click",function(){commentLike(false)});
const cul=document.getElementById("ctCommentUnlikeBtn");if(cul)cul.addEventListener("click",function(){commentLike(true)});
const cd=document.getElementById("ctCommentDeleteBtn");if(cd)cd.addEventListener("click",commentDelete);
const rl=document.getElementById("ctRoleBtn");if(rl)rl.addEventListener("click",changeRole);
const rcm=document.getElementById("ctRoomCommentsBtn");if(rcm)rcm.addEventListener("click",showRoomComments);
const cj=document.getElementById("ctCancelJoinBtn");if(cj)cj.addEventListener("click",cancelJoin);
}
if(document.readyState==="loading"){document.addEventListener("DOMContentLoaded",initCommTools)}else{initCommTools()}
})();

// ==== 投稿いいねした人(feed_post_liked_usersフォールバック) + マイページ装飾表示 ====
(function(){
if(window.__koeLikersDeco)return;window.__koeLikersDeco=true;
function esc(s){try{return escapeHtml(String(s==null?"":s))}catch(e){return String(s==null?"":s)}}

// 投稿詳細の「いいねした人」が空だった場合、feed API 側のエンドポイントで補完
if(typeof window.openPostDetail==="function"){
const _origOPD=window.openPostDetail;
window.openPostDetail=function(evt,postId){
const ret=_origOPD(evt,postId);
setTimeout(async function(){
const likers=document.getElementById("pdLikers");
if(!likers)return;
const txt=likers.textContent||"";
const hasContent=likers.querySelector("img")||(txt&&txt.indexOf("読み込み中")<0&&txt.replace(/\s/g,"").length>0&&txt.indexOf("いません")<0&&txt.indexOf("なし")<0);
if(hasContent)return;
let r;try{r=await callApi("get_feed_post_liked_users",String(postId))}catch(e){r=null}
if(r&&r.ok&&r.users&&r.users.length){
likers.innerHTML=r.users.map(function(u){var inner=typeof avatarHtml==="function"?avatarHtml(u.name,u.icon_url):esc(u.name||"");return '<span class="pd-liker" onclick="viewProfile('+(u.user_id||0)+')" style="cursor:pointer;" title="プロフィールを見る">'+inner+esc(u.name||"")+'</span>'}).join("");
const cnt=document.getElementById("pdLikeCount");if(cnt&&!cnt.textContent)cnt.textContent=r.users.length;
}
},1300);
return ret;
};
}

// マイページ装飾の現在値を表示
{
const btn=document.getElementById("mypageDecoBtn");
if(btn)btn.addEventListener("click",async function(){
const box=document.getElementById("decorationItemsList");
if(box)box.innerHTML='<div class="empty-msg">読み込み中...</div>';
let r;try{r=await callApi("get_mypage_decoration")}catch(e){r=null}
if(!r||!r.ok){if(box)box.innerHTML='<div class="empty-msg">取得できませんでした</div>';return}
const d=r.data||r;
if(box)box.innerHTML='<div class="card" style="cursor:default;"><div class="card-body"><div class="card-name">現在のマイページ装飾</div><div class="card-sub" style="white-space:pre-wrap;opacity:.85;">'+esc(JSON.stringify(d,null,2).slice(0,800))+'</div></div></div>';
});
}
})();

// ==== 残りの小物: 購読導入スケジュール / ルール削除 / 投稿低評価 / チャット一括削除 ====
(function(){
if(window.__koeFinalExtras)return;window.__koeFinalExtras=true;
function esc(s){try{return escapeHtml(String(s==null?"":s))}catch(e){return String(s==null?"":s)}}
function toastMsg(m,t){try{toast(m,t)}catch(e){}}

// 購読の導入スケジュール
{
const b=document.getElementById("subsIntroBtn");
if(b)b.addEventListener("click",async function(){
const box=document.getElementById("subscriptionsList");if(box)box.innerHTML='<div class="empty-msg">読み込み中...</div>';
let r;try{r=await callApi("get_subscription_introduction_schedules")}catch(e){r=null}
if(!r||!r.ok){if(box)box.innerHTML='<div class="empty-msg">取得できませんでした</div>';return}
const items=r.schedules||[];
if(!items.length){if(box)box.innerHTML='<div class="empty-msg">スケジュールはありません</div>';return}
if(box)box.innerHTML=items.map(function(s){return '<div class="card" style="cursor:default;"><div class="card-body"><div class="card-sub" style="white-space:pre-wrap;opacity:.85;">'+esc(JSON.stringify(s).slice(0,300))+'</div></div></div>'}).join("");
});
}

// コミュニティのルール削除
{
const b=document.getElementById("ctRuleDeleteBtn");
if(b)b.addEventListener("click",async function(){
const cid=(typeof currentCommunity!=="undefined"&&currentCommunity)?currentCommunity.id:null;
const rid=((document.getElementById("ctRuleId")||{}).value||"").trim();
if(!cid||!rid){toastMsg("rule_id を入力してください","error");return}
let ok=false;try{ok=await showConfirmModal("このルールを削除しますか?")}catch(e){ok=false}
if(!ok)return;
let r;try{r=await callApi("delete_community_rule",String(cid),String(rid))}catch(e){r=null}
toastMsg(r&&r.ok?"ルールを削除しました":"削除に失敗しました",r&&r.ok?undefined:"error");
});
}

// 投稿の低評価/報告
{
const b=document.getElementById("pdBadVoteBtn");
if(b)b.addEventListener("click",async function(){
const pid=window.__koeCurrentFeedPostId;
if(!pid){toastMsg("投稿が不明です","error");return}
let reason="";try{reason=prompt("理由(任意)")}catch(e){}
if(reason===null)return;
let ok=false;try{ok=await showConfirmModal("この投稿を低評価/報告しますか?")}catch(e){ok=false}
if(!ok)return;
b.disabled=true;
let r;try{r=await callApi("feed_post_bad_vote",String(pid),String(reason||""))}catch(e){r=null}
toastMsg(r&&r.ok?"送信しました":"送信に失敗しました",r&&r.ok?undefined:"error");b.disabled=false;
});
}

// チャット一括削除
{
const b=document.getElementById("bulkChatDeleteBtn");
if(b)b.addEventListener("click",async function(){
const ids=((document.getElementById("bulkChatIdsInput")||{}).value||"").trim();
if(!ids){toastMsg("chat_id を入力してください","error");return}
let ok=false;try{ok=await showConfirmModal("入力したチャットを一括削除します。取り消せませんがよろしいですか?")}catch(e){ok=false}
if(!ok)return;
b.disabled=true;
let r;try{r=await callApi("bulk_delete_chats",ids)}catch(e){r=null}
toastMsg(r&&r.ok?"削除しました":"削除に失敗しました",r&&r.ok?undefined:"error");b.disabled=false;
if(r&&r.ok){const inp=document.getElementById("bulkChatIdsInput");if(inp)inp.value=""}
});
}
})();

// ==== 応援トーク(1対1) 通話フローのサーバー側接続登録を補完 ====
// confirm後にcheering_skyway_connect、キャンセル/切断時にdisconnectを呼び、
// サーバー側の接続状態を正しく確立/解放する(音声SDKの実接続は別途実機検証が必要)。
(function(){
if(window.__koeCheeringConnFix)return;window.__koeCheeringConnFix=true;

if(typeof window.confirmCheeringCall==="function"){
const _origConfirm=window.confirmCheeringCall;
window.confirmCheeringCall=async function(channel,target,name){
const ret=await _origConfirm(channel,target,name);
try{
if(target){
// 1) サーバー側にSkyWay接続を登録
const r=await callApi("cheering_skyway_connect",String(target),channel?String(channel):"");
if(typeof appendCheeringDebug==="function")appendCheeringDebug("cheering_skyway_connect",r);
// 2) 実際の音声接続: チャンネルのSkyWayトークンを取得してp2p通話を確立
if(channel&&typeof startInWindowCall==="function"){
try{if(typeof showCheeringCallStatus==="function")showCheeringCallStatus("音声を接続しています...",!1)}catch(e){}
const vc=await callApi("get_cheering_voice_call",String(channel),String(target));
if(typeof appendCheeringDebug==="function")appendCheeringDebug("get_cheering_voice_call",vc);
if(vc&&vc.ok&&vc.call){
// 接続中フラグでteardownの誤切断を抑止しつつ、__koeCheeringActiveは常に設定しておく(失敗時もサーバー側接続を確実に解放するため)
window.__koeCheeringConnecting=true;
window.__koeCheeringActive={target:String(target),channel:String(channel)};
try{
await startInWindowCall(vc.call);
try{if(typeof showCheeringCallStatus==="function")showCheeringCallStatus("通話に接続しました",!1)}catch(e){}
}finally{
window.__koeCheeringConnecting=false;
}
}else{
// 音声接続に失敗しても、サーバー側の接続登録は行われている
window.__koeCheeringActive={target:String(target),channel:channel?String(channel):""};
try{if(typeof showCheeringCallStatus==="function")showCheeringCallStatus("音声接続に失敗しました("+JSON.stringify(vc&&(vc.error||vc.message||vc.status)||"").slice(0,120)+")",!1)}catch(e){}
}
}else{
window.__koeCheeringActive={target:String(target),channel:channel?String(channel):""};
}
}
}catch(e){try{if(typeof appendCheeringDebug==="function")appendCheeringDebug("cheering_voice_error",String(e))}catch(_){}}
return ret;
};
}

async function cheeringDisconnectNow(){
const a=window.__koeCheeringActive;
if(!a||!a.target)return;
window.__koeCheeringActive=null;
try{
const r1=await callApi("cheering_skyway_disconnect",a.target,a.channel||"");
if(typeof appendCheeringDebug==="function")appendCheeringDebug("cheering_skyway_disconnect",r1);
}catch(e){}
try{
const r2=await callApi("disconnect_cheering_call",a.target);
if(typeof appendCheeringDebug==="function")appendCheeringDebug("disconnect_cheering_call",r2);
}catch(e){}
}

if(typeof window.cancelCheeringCall==="function"){
const _origCancel=window.cancelCheeringCall;
window.cancelCheeringCall=async function(){
const ret=await _origCancel();
try{await cheeringDisconnectNow()}catch(e){}
return ret;
};
}

// 通話終了(teardownCall)時にも応援トークの切断を確実に呼ぶ
if(typeof window.teardownCall==="function"){
const _origTeardown=window.teardownCall;
window.teardownCall=function(notifyServer){
if(window.__koeCheeringActive&&!window.__koeCheeringConnecting){try{cheeringDisconnectNow()}catch(e){}}
return _origTeardown(notifyServer);
};
}
window.koeCheeringDisconnect=cheeringDisconnectNow;
})();

// ==== 診断ログ: 全API呼び出しの成否/ステータス/エラー + JS例外 + ネイティブHTTPログを可視化 ====
(function(){
if(window.__koeDbgInit)return;window.__koeDbgInit=true;
function esc(s){try{return escapeHtml(String(s==null?"":s))}catch(e){return String(s==null?"":s).replace(/[&<>]/g,function(c){return{"&":"&amp;","<":"&lt;",">":"&gt;"}[c]})}}
const LOGCAP=1000;
window.__koeLog=window.__koeLog||[];
function pushLog(entry){window.__koeLog.push(entry);if(window.__koeLog.length>LOGCAP)window.__koeLog.splice(0,window.__koeLog.length-LOGCAP);if(__auto&&isOpen())renderLog();}
function nowT(){try{const d=new Date();return d.toTimeString().slice(0,8)+"."+String(d.getMilliseconds()).padStart(3,"0")}catch(e){return""}}

// callApi をラップして全呼び出しを記録(ログ管理系コマンド自身は記録しない)
const SKIP=new Set(["get_native_log","clear_native_log","set_debug_log_enabled"]);
if(typeof window.callApi==="function"){
const _origCallApi=window.callApi;
window.callApi=function(methodName,...args){
if(SKIP.has(methodName))return _origCallApi(methodName,...args);
const t0=(function(){try{return performance.now()}catch(e){return 0}})();
let p;
try{p=_origCallApi(methodName,...args)}catch(e){
pushLog({t:nowT(),kind:"api",method:methodName,args:args,ok:false,status:0,error:"呼び出し例外: "+(e&&e.message||e),ms:0});
throw e;
}
Promise.resolve(p).then(function(r){
const ms=Math.round(((function(){try{return performance.now()}catch(e){return 0}})())-t0);
const ok=!!(r&&r.ok);
pushLog({t:nowT(),kind:"api",method:methodName,args:args,ok:ok,status:(r&&(r.status!=null?r.status:(ok?200:0)))||0,error:ok?"":(r&&(r.error||r.message||r.raw)||"(ok=false)"),ms:ms,raw:r&&r.raw});
},function(e){
const ms=Math.round(((function(){try{return performance.now()}catch(e){return 0}})())-t0);
pushLog({t:nowT(),kind:"api",method:methodName,args:args,ok:false,status:0,error:"reject: "+(e&&e.message||e),ms:ms});
});
return p;
};
}

// JS例外の捕捉
window.addEventListener("error",function(e){
try{pushLog({t:nowT(),kind:"js",method:"window.onerror",ok:false,error:(e&&e.message||"error")+" @ "+((e&&e.filename||"").split("/").pop())+":"+(e&&e.lineno||"")})}catch(_){}
});
window.addEventListener("unhandledrejection",function(e){
try{var r=e&&e.reason;pushLog({t:nowT(),kind:"js",method:"unhandledrejection",ok:false,error:(r&&(r.message||r.stack)||String(r))})}catch(_){}
});

// ネイティブHTTPログの取り込み
let __nativeSeen=0;
async function pullNative(){
let r;try{r=await window.pywebview.api.get_native_log()}catch(e){r=null}
if(!r||!r.ok||!r.log)return;
// 差分だけ追加(全置換だとJSログと混ざるので、ネイティブ分は別枠でマージ)
window.__koeNativeLog=r.log;
if(isOpen())renderLog();
}

// ---- ビューア ----
let __filter="all",__auto=true;
function isOpen(){const m=document.getElementById("koeDbgModal");return m&&m.style.display!=="none"}
function entryText(e){
if(e.kind==="js")return e.t+"  [JS] "+e.method+"  "+(e.error||"");
let a="";try{a=e.args&&e.args.length?"("+e.args.map(x=>typeof x==="string"?(x.length>80?x.slice(0,48)+"…["+x.length+"字]":x):JSON.stringify(x)).join(", ")+")":"()"}catch(_){a="()"}
const head=e.t+"  "+e.method+a+"  → "+(e.ok?"OK":"失敗")+(e.status?(" ["+e.status+"]"):"")+(e.ms?(" "+e.ms+"ms"):"");
const detail=e.ok?"":("  "+(e.error||""));
return head+detail;
}
function renderLog(){
const box=document.getElementById("koeDbgList");if(!box)return;
const js=(window.__koeLog||[]).map(e=>Object.assign({src:"js"},e));
const nat=(window.__koeNativeLog||[]).map(s=>({src:"nat",kind:"net",raw:s}));
let items=js.concat(nat);
// フィルタ
items=items.filter(function(e){
if(__filter==="all")return true;
if(__filter==="err")return (e.kind==="net")?/✗|通信エラー|→ [45]\d\d|→ -1/.test(e.raw||""):(e.ok===false);
if(__filter==="api")return e.kind==="api";
if(__filter==="net")return e.kind==="net";
if(__filter==="js")return e.kind==="js";
return true;
});
// 新しい順(JSは配列末尾が新しい、ネイティブも末尾が新しい)。単純に元順を反転。
items=items.reverse();
const cnt=document.getElementById("koeDbgCount");if(cnt)cnt.textContent="("+items.length+"件)";
if(!items.length){box.innerHTML='<div style="opacity:.6;">ログはまだありません。アプリを操作すると記録されます。</div>';return}
box.innerHTML=items.slice(0,600).map(function(e){
if(e.kind==="net"){
const bad=/✗|通信エラー|→ [45]\d\d|→ -1/.test(e.raw||"");
return '<div style="padding:2px 0;border-bottom:1px solid rgba(128,128,128,.12);color:'+(bad?"#ff6b6b":"#8fd18f")+';white-space:pre-wrap;word-break:break-all;">'+esc(e.raw)+'</div>';
}
const color=e.kind==="js"?"#ffb454":(e.ok?"#8fd18f":"#ff6b6b");
return '<div style="padding:2px 0;border-bottom:1px solid rgba(128,128,128,.12);color:'+color+';white-space:pre-wrap;word-break:break-all;">'+esc(entryText(e))+'</div>';
}).join("");
}
function allText(){
const js=(window.__koeLog||[]).map(entryText);
const nat=(window.__koeNativeLog||[]).slice();
return "=== KoeTomo 診断ログ ===\nbuild: v1.01\n[JS/API]\n"+js.join("\n")+"\n\n[ネイティブHTTP]\n"+nat.join("\n");
}
function openDbg(){const m=document.getElementById("koeDbgModal");if(m){m.style.display="flex";pullNative();renderLog()}}
/* ---- ログイン失敗時に診断ログを自動表示(友人テスト用) ---- */
function __koeAutoDiagOnFail(){
  try{
    setTimeout(function(){
      try{
        if(window.openDbg) window.openDbg();
        else { var m=document.getElementById("koeDbgModal"); if(m)m.style.display="flex"; }
      }catch(e){}
      // 全文をクリップボードにもコピー(共有しやすく)
      try{
        if(typeof allText==="function" && navigator.clipboard && navigator.clipboard.writeText){
          navigator.clipboard.writeText(allText()).catch(function(){});
        }
      }catch(e){}
      try{ if(typeof toast==="function") toast("ログイン失敗。診断ログを表示しました(コピー済み)","error"); }catch(e){}
    }, 400);
  }catch(e){}
}

function closeDbg(){const m=document.getElementById("koeDbgModal");if(m)m.style.display="none"}

function initDbg(){
const b=document.getElementById("koeDbgBtn");if(b)b.addEventListener("click",openDbg);
const c=document.getElementById("koeDbgClose");if(c)c.addEventListener("click",closeDbg);
const m=document.getElementById("koeDbgModal");if(m)m.addEventListener("click",function(e){if(e.target===m)closeDbg()});
document.querySelectorAll(".koe-dbg-filter").forEach(function(ch){ch.addEventListener("click",function(){__filter=ch.dataset.f;document.querySelectorAll(".koe-dbg-filter").forEach(x=>x.classList.toggle("active",x===ch));renderLog()})});
const auto=document.getElementById("koeDbgAuto");if(auto)auto.addEventListener("change",function(){__auto=auto.checked});
const rf=document.getElementById("koeDbgRefresh");if(rf)rf.addEventListener("click",function(){pullNative();renderLog()});
const pb=document.getElementById("koeDbgProbe");if(pb)pb.addEventListener("click",async function(){pb.disabled=true;const _t=pb.textContent;pb.textContent="調査中...";try{await window.pywebview.api.probe_endpoints()}catch(e){}try{await pullNative()}catch(e){}__filter="all";renderLog();pb.disabled=false;pb.textContent=_t;try{toast&&toast("調査完了。ログの[PROBE]行を確認/コピーしてください")}catch(e){}});
const cp=document.getElementById("koeDbgCopy");if(cp)cp.addEventListener("click",async function(){
const txt=allText();
try{if(window.AndroidApi&&window.AndroidApi.shareText){window.AndroidApi.shareText(txt);return}}catch(e){}
try{await navigator.clipboard.writeText(txt);toast&&toast("ログをコピーしました");return}catch(e){}
// フォールバック: テキストエリアに表示
const box=document.getElementById("koeDbgList");if(box)box.innerHTML='<textarea readonly style="width:100%;height:100%;min-height:300px;font-size:11px;">'+esc(txt)+'</textarea>';
});
const cl=document.getElementById("koeDbgClear");if(cl)cl.addEventListener("click",async function(){
window.__koeLog=[];window.__koeNativeLog=[];
try{await window.pywebview.api.clear_native_log()}catch(e){}
renderLog();
});
// ネイティブログを定期取り込み(モーダルを開いている時のみ)
setInterval(function(){if(__auto&&isOpen())pullNative()},4000);
}
if(document.readyState==="loading"){document.addEventListener("DOMContentLoaded",initDbg)}else{initDbg()}
})();

// ==== 応援トークランキング: 公式Webページ方式 ====
// 応援ランキングは API リストではなく r.koetomo.fun の月次Webページ。
// 「ランキング」チップはキャプチャ段でインターセプトし、公式ランキングページを開く。
(function(){
  if(window.__koeRankWeb) return; window.__koeRankWeb=true;
  function openExt(url){
    try{ if(window.AndroidApi&&window.AndroidApi.openUrl){window.AndroidApi.openUrl(url);return} }catch(e){}
    try{ window.open(url,"_blank") }catch(e2){}
  }
  function fallbackUrl(){
    try{ var d=new Date(); var ym=""+d.getFullYear()+String(d.getMonth()+1).padStart(2,"0");
      return "https://r.koetomo.fun/ranking/cheering_talk_"+ym; }
    catch(e){ return "https://r.koetomo.fun/ranking/cheering_talk_202503"; }
  }
  function openRankingWeb(){
    (async function(){
      var url="";
      try{ var r=await callApi("get_cheering_ranking_url"); if(r&&r.ok&&r.url) url=r.url; }catch(e){}
      if(!url) url=fallbackUrl();
      try{ toast&&toast("公式ランキングページを開きます") }catch(e){}
      openExt(url);
    })();
  }
  function bind(){
    // アプリ内でランキング API を表示できるようになったため、Webページ差し替えは無効化。
    // (get_receivers("rankings") が公式ランキングを返す。openRankingWeb は未使用で温存)
    return;
  }
  if(false){ bind(); openRankingWeb(); }
})();

// ==== 公式リンク: プロフィール直下に直タップ配置 ====
(function(){
  if(window.__koeOfficialDirect) return; window.__koeOfficialDirect=true;
  function esc(s){try{return escapeHtml(String(s==null?"":s))}catch(e){return""}}
  function escA(s){try{return escAttr(String(s==null?"":s))}catch(e){return esc(s)}}
  function openExt(url){try{if(window.AndroidApi&&window.AndroidApi.openUrl){window.AndroidApi.openUrl(url);return}}catch(e){}try{window.open(url,"_blank")}catch(e2){}}
  var loaded=false, loading=false;
  async function load(){
    var box=document.getElementById("officialLinksDirect"); if(!box) return;
    if(loaded||loading) return; loading=true;
    box.innerHTML='<div class="empty-msg">読み込み中...</div>';
    var r; try{ r=await callApi("get_official_links"); }catch(e){ r=null; }
    loading=false;
    if(!r||!r.ok||!r.links||!r.links.length){ box.innerHTML='<div class="empty-msg">リンクを取得できませんでした</div>'; return; }
    loaded=true;
    box.innerHTML=r.links.map(function(l){
      var u=l.url||"";
      return '<button class="btn-secondary koe-oflink" style="width:100%;text-align:left;" data-url="'+escA(u)+'">\uD83D\uDD17 '+esc(l.title||"")+' <span class="uid-tag" style="margin-left:6px;">'+esc(l.category||"")+'</span></button>';
    }).join("");
    Array.prototype.forEach.call(box.querySelectorAll(".koe-oflink"),function(b){
      b.addEventListener("click",function(){ var u=b.getAttribute("data-url"); if(u) openExt(u); });
    });
  }
  // マイページが表示されたら一度だけロード
  document.addEventListener("click",function(){
    setTimeout(function(){ var p=document.getElementById("page-mypage"); if(p&&p.classList.contains("active")) load(); },200);
  },true);
  setTimeout(function(){ var p=document.getElementById("page-mypage"); if(p&&p.classList.contains("active")) load(); },1800);
})();

// ==== メール/パスワード保存 + 自動再ログイン(オプトイン) ====
(function(){
  if(window.__koeAutoLoginInit) return; window.__koeAutoLoginInit=true;
  function b64e(s){try{return btoa(unescape(encodeURIComponent(String(s==null?"":s))))}catch(e){return""}}
  function b64d(s){try{return decodeURIComponent(escape(atob(String(s||""))))}catch(e){return""}}
  function enabled(){try{return localStorage.getItem("koe_autologin")==="1"}catch(e){return false}}
  /* 自動ログイン用の資格情報は Keystore 暗号化ストア(secureSave)に保存。旧版の localStorage(base64)は初回に移行して削除 */
  function __sec(){try{var a=window.AndroidApi;return (a&&a.secureSave&&a.secureLoad)?a:null}catch(e){return null}}
  function saveCred(email,password){var j=JSON.stringify({e:b64e(email),p:b64e(password)});var a=__sec();if(a){try{a.secureSave("autologin_cred",j);localStorage.removeItem("koe_autologin_cred");return}catch(e){}}try{localStorage.setItem("koe_autologin_cred",j)}catch(e){}}
  function clearCred(){try{localStorage.removeItem("koe_autologin_cred")}catch(e){}try{var a=__sec();if(a)a.secureSave("autologin_cred","")}catch(e){}}
  function getCred(){try{var raw=null;var a=__sec();if(a){try{raw=a.secureLoad("autologin_cred")||null}catch(e){}}if(!raw){raw=localStorage.getItem("koe_autologin_cred");if(raw&&a){try{a.secureSave("autologin_cred",raw);localStorage.removeItem("koe_autologin_cred")}catch(e){}}}var c=JSON.parse(raw||"null");if(!c)return null;return {email:b64d(c.e),password:b64d(c.p)}}catch(e){return null}}
  window.__koeOnLoginSuccess=function(email,password){try{if(enabled()&&email&&password)saveCred(email,password)}catch(e){}};
  window.__koeCredRelogin=async function(){
    try{
      if(!enabled())return false;
      var c=getCred(); if(!c||!c.email||!c.password)return false;
      var r=await api().login(c.email,c.password);
      if(r&&r.ok){try{await saveCurrentAccount()}catch(e){}return true;}
    }catch(e){}
    return false;
  };
  function wire(){
    var chk=document.getElementById("koeAutoLoginChk");
    if(!chk||chk.__wired)return; chk.__wired=true;
    try{chk.checked=enabled()}catch(e){}
    chk.addEventListener("change",function(){
      try{
        if(chk.checked){localStorage.setItem("koe_autologin","1");try{toast("次回ログイン時に認証情報を保存します")}catch(e){}}
        else{localStorage.setItem("koe_autologin","0");clearCred();try{toast("保存した認証情報を削除しました")}catch(e){}}
      }catch(e){}
    });
  }
  if(document.readyState==="loading"){document.addEventListener("DOMContentLoaded",wire)}else{wire()}
  setTimeout(wire,1000); setTimeout(wire,3000);
})();

// ==== 追加改善: OS視差軽減の尊重 + チャット一覧の絞り込み ====
(function(){
  try{
    if(localStorage.getItem("koe_anim")==null && window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches){
      document.body.classList.add("no-anim");
    }
  }catch(e){}
  function wireChatSearch(){
    var inp=document.getElementById("chatSearchInput"); if(!inp||inp.__wired)return; inp.__wired=true;
    inp.addEventListener("input",function(){
      var q=(inp.value||"").trim().toLowerCase();
      var list=document.getElementById("chatList"); if(!list)return;
      Array.prototype.forEach.call(list.querySelectorAll(".card"),function(card){
        var t=(card.textContent||"").toLowerCase();
        card.style.display=(!q||t.indexOf(q)>=0)?"":"none";
      });
    });
  }
  if(document.readyState==="loading"){document.addEventListener("DOMContentLoaded",wireChatSearch)}else{wireChatSearch()}
  setTimeout(wireChatSearch,1500);
})();

// ==== 自由度: タブ表示/非表示 + 起動タブ + セクション記憶 + TLフィルタ記憶 ====
(function(){
  if(window.__koeFreedom) return; window.__koeFreedom=true;
  var HIDE_KEY="koe_nav_hidden", LAND_KEY="koe_landing", SEC_KEY="koe_open_sections", FEED_KEY="koe_feed";
  function getHidden(){try{var a=JSON.parse(localStorage.getItem(HIDE_KEY)||"[]");return Array.isArray(a)?a.filter(function(v){return v!=="mypage"}):[]}catch(e){return[]}}
  function setHidden(a){try{localStorage.setItem(HIDE_KEY,JSON.stringify(a))}catch(e){}}
  function navList(){ try{ return (typeof NAV_DEFAULT!=="undefined"&&NAV_DEFAULT)?NAV_DEFAULT.slice():["timeline","call","cheering","chat","talk","community","notifications","mypage"]; }catch(e){ return ["timeline","mypage"]; } }
  function label(v){ try{ return (typeof NAV_LABELS!=="undefined"&&NAV_LABELS[v])||v; }catch(e){ return v; } }
  function applyNavHidden(){
    try{
      var hidden=getHidden(), rail=document.querySelector(".rail"); if(!rail)return;
      navList().forEach(function(v){
        var it=rail.querySelector('.rail-item[data-view="'+v+'"]');
        if(it) it.style.display = (v!=="mypage" && hidden.indexOf(v)>=0) ? "none" : "";
      });
    }catch(e){}
  }
  // 起動タブ(表示中の先頭にフォールバック)
  window.__koeStartTab=function(){
    try{
      var hidden=getHidden();
      var landing=localStorage.getItem(LAND_KEY)||"last";
      var tab=(landing&&landing!=="last")?landing:(localStorage.getItem("koe_last_tab")||"timeline");
      if(hidden.indexOf(tab)>=0 || !document.getElementById("page-"+tab)){
        var order=(typeof getNavOrder==="function")?getNavOrder():navList();
        tab=order.filter(function(v){return hidden.indexOf(v)<0})[0]||"timeline";
      }
      return tab;
    }catch(e){ return "timeline"; }
  };
  function renderLandingSel(){
    var sel=document.getElementById("koeLandingSel"); if(!sel||sel.__wired)return; sel.__wired=true;
    var cur=localStorage.getItem(LAND_KEY)||"last";
    var opts='<option value="last">前回の続き</option>';
    navList().forEach(function(v){ opts+='<option value="'+v+'">'+label(v)+'</option>'; });
    sel.innerHTML=opts; try{sel.value=cur}catch(e){}
    sel.addEventListener("change",function(){ try{localStorage.setItem(LAND_KEY,sel.value);toast&&toast("起動タブを保存しました")}catch(e){} });
  }
  function renderNavVisibility(){
    var box=document.getElementById("navVisibilityList"); if(!box)return;
    var hidden=getHidden();
    box.innerHTML=navList().map(function(v){
      var dis=(v==="mypage");
      var checked=(dis||hidden.indexOf(v)<0)?"checked":"";
      return '<label class="nav-order-item" style="cursor:pointer;"><span>'+label(v)+(dis?'（常に表示）':'')+'</span>'
        +'<input type="checkbox" data-view="'+v+'" '+checked+' '+(dis?'disabled':'')+'></label>';
    }).join("");
    Array.prototype.forEach.call(box.querySelectorAll('input[type=checkbox]'),function(cb){
      cb.addEventListener("change",function(){
        var v=cb.getAttribute("data-view"), hidden=getHidden(), i=hidden.indexOf(v);
        if(cb.checked){ if(i>=0)hidden.splice(i,1); } else { if(i<0)hidden.push(v); }
        setHidden(hidden); applyNavHidden();
      });
    });
  }
  // セクションの開閉状態を記憶(idを持つ .mypage-section のみ)
  function getOpenSecs(){try{return JSON.parse(localStorage.getItem(SEC_KEY)||"[]")}catch(e){return[]}}
  function hookSections(){
    Array.prototype.forEach.call(document.querySelectorAll("details.mypage-section[id]"),function(d){
      if(d.__secHook)return; d.__secHook=true;
      d.addEventListener("toggle",function(){
        try{ var o=getOpenSecs(), id=d.id, i=o.indexOf(id);
          if(d.open){ if(i<0)o.push(id); } else { if(i>=0)o.splice(i,1); }
          localStorage.setItem(SEC_KEY,JSON.stringify(o));
        }catch(e){}
      });
    });
  }
  function restoreSections(){
    try{ var o=getOpenSecs(); o.forEach(function(id){ var d=document.getElementById(id); if(d&&d.tagName==="DETAILS"&&!d.open){ d.open=true; } }); }catch(e){}
  }
  // TLフィルタ(すべて/フォロー中/画像/音声)の選択を記憶
  function persistFeed(){
    try{
      document.addEventListener("click",function(ev){
        var chip=ev.target&&ev.target.closest&&ev.target.closest(".feed-chip,[data-feed]");
        if(chip){ var f=chip.getAttribute("data-feed"); if(f){ try{localStorage.setItem(FEED_KEY,f)}catch(e){} } }
      },true);
    }catch(e){}
  }
  function boot(){
    applyNavHidden(); renderLandingSel(); renderNavVisibility(); hookSections(); persistFeed();
    // マイページを開いたらセクション復元 + 各UI再描画
    document.addEventListener("click",function(){
      setTimeout(function(){ var p=document.getElementById("page-mypage"); if(p&&p.classList.contains("active")){ hookSections(); restoreSections(); renderLandingSel(); renderNavVisibility(); } },250);
    },true);
  }
  if(document.readyState==="loading"){document.addEventListener("DOMContentLoaded",boot)}else{boot()}
  setTimeout(boot,1200); setTimeout(applyNavHidden,2500);
})();

// ==== TL「もっと読む」境界の重複を除去(appendのみ、fresh loadは必ずリセットしてから) ====
(function(){
  if(window.__tlSeen) return;
  window.__tlSeen=new Set();
  window.__tlSeenReset=function(){try{window.__tlSeen.clear()}catch(e){}};
  window.__tlKeep=function(id,append){
    try{
      if(id==null) return true;
      var k=String(id);
      if(append && window.__tlSeen.has(k)) return false; // 追加読み込みで既出なら除外
      window.__tlSeen.add(k);
    }catch(e){}
    return true;
  };
})();

// ==== 投稿先の2択(タイムライン/通話募集)をhidden selectに同期 ====
(function(){
  if(window.__koePostDest) return; window.__koePostDest=true;
  var bound=false;
  function sel(){ return document.getElementById("composeTopic"); }
  function paint(){ Array.prototype.forEach.call(document.querySelectorAll('input[name="postDest"]'),function(rb){ var lab=rb.closest&&rb.closest("label"); if(lab) lab.style.borderColor=rb.checked?"var(--accent,#4a90d9)":"rgba(128,128,128,.35)"; }); }
  function fromRadio(){ var r=document.querySelector('input[name="postDest"]:checked'), s=sel(); if(r&&s){ s.value=r.value; try{localStorage.setItem("koe_last_topic",r.value)}catch(e){} } paint(); }
  function fromSel(){ var s=sel(); if(!s)return; var v=(s.value==="5")?"5":"0"; Array.prototype.forEach.call(document.querySelectorAll('input[name="postDest"]'),function(rb){ rb.checked=(rb.value===v); }); fromRadio(); }
  function bind(){ var rs=document.querySelectorAll('input[name="postDest"]'); if(!rs.length||bound)return; bound=true; Array.prototype.forEach.call(rs,function(rb){ rb.addEventListener("change",fromRadio); }); paint(); }
  if(document.readyState==="loading"){document.addEventListener("DOMContentLoaded",bind)}else{bind()}
  setTimeout(bind,1200);
  document.addEventListener("click",function(){ setTimeout(function(){ var m=document.getElementById("composeModal"); if(m&&getComputedStyle(m).display!=="none"){ bind(); fromSel(); } },120); },true);
})();
// ==== 通話: 明示的に「新しく通話を開く」→ 作成オプション(枠名/公開/コメント)を表示 ====
(function(){
  function bind(){
    var b=document.getElementById("openNewCallBtn");
    if(!b||b.__koeNewCallBound)return;
    b.__koeNewCallBound=true;
    b.addEventListener("click",function(){
      var r=document.getElementById("createRoomRow");
      if(r){
        r.style.display="flex";
        try{
          var def=(typeof getRoomDefaults==="function")?getRoomDefaults():{};
          var dd=document.getElementById("createRoomDesc");
          if(dd&&!dd.value&&def.title)dd.value=def.title;
          var pp=document.getElementById("createRoomPublic");
          if(pp&&def.public!==undefined)pp.checked=def.public!==false;
          var cc=document.getElementById("createRoomComment");
          if(cc&&def.comment!==undefined)cc.checked=def.comment!==false;
        }catch(e){}
        var d=document.getElementById("createRoomDesc");
        if(d){try{d.focus();}catch(e){}}
        try{r.scrollIntoView({behavior:"smooth",block:"nearest"});}catch(e){}
        try{if(window.setCallStatus)setCallStatus("枠の設定を入力して「この設定で枠を作る」を押してください。");}catch(e){}
      }
    });
  }
  if(document.readyState==="loading"){document.addEventListener("DOMContentLoaded",bind);}else{bind();}
  setTimeout(bind,1200);
})();

/* ============================================================
   v55 追加機能まとめ
   ============================================================ */

/* ---- 診断ログ: 設定内のボタンから開く ---- */
(function(){
  function bind(){
    var ids=["koeDbgOpen","koeDbgOpenLogin"];
    for(var i=0;i<ids.length;i++){
      var b=document.getElementById(ids[i]);
      if(b&&!b.__koeBound){ b.__koeBound=true; b.addEventListener("click",function(){ try{ if(window.openDbg) return openDbg(); var m=document.getElementById("koeDbgModal"); if(m)m.style.display="flex"; }catch(e){} }); }
    }
  }
  if(document.readyState==="loading")document.addEventListener("DOMContentLoaded",bind);else bind();
  setTimeout(bind,1200);
})();

/* ---- チャット: 複数行入力(改行OK) + 写真送信 ---- */
(function(){
  var pendingImg=null;
  function grow(ta){ if(!ta)return; ta.style.height="auto"; ta.style.height=Math.min(ta.scrollHeight,120)+"px"; }
  function setPreview(dataUrl,name){
    pendingImg=dataUrl||null;
    var row=document.getElementById("chatImagePreviewRow"),img=document.getElementById("chatImagePreview"),nm=document.getElementById("chatImageName");
    if(dataUrl){ if(img)img.src=dataUrl; if(nm)nm.textContent=name||"写真"; if(row)row.style.display="flex"; }
    else { if(row)row.style.display="none"; if(img)img.src=""; }
  }
  async function koeSend(){
    if(typeof currentChat==="undefined"||!currentChat)return;
    if(window.__chatSending)return; window.__chatSending=true; setTimeout(function(){window.__chatSending=false;},1500);
    var ta=document.getElementById("chatInput");
    var text=ta?ta.value.trim():"";
    if(pendingImg){
      var data=pendingImg; setPreview(null);
      var btn=document.getElementById("chatSendBtn"); if(btn)btn.disabled=true;
      try{
        var r=await callApi("send_image_message",currentChat.chatId,currentChat.targetId,data);
        if(r&&r.ok){ if(text){ try{await callApi("send_message",currentChat.chatId,currentChat.targetId,text);}catch(e){} } if(ta){ta.value="";grow(ta);} await reloadMessages(); }
        else { toast("写真の送信に失敗: "+JSON.stringify((r&&(r.body||r.error))||"").slice(0,110),"error"); setPreview(data,"写真"); }
      }catch(e){ toast("写真の送信に失敗しました","error"); setPreview(data,"写真"); }
      if(btn)btn.disabled=false;
      return;
    }
    if(!text)return;
    if(window.sendChatMessage){ sendChatMessage(); setTimeout(function(){grow(document.getElementById("chatInput"));},50); }
  }
  window.koeSendChat=koeSend;
  function init(){
    var ta=document.getElementById("chatInput");
    if(ta&&ta.tagName==="TEXTAREA"&&!ta.__koeMulti){
      ta.__koeMulti=true;
      var clone=ta.cloneNode(true); ta.parentNode.replaceChild(clone,ta); ta=clone; // 旧 Enter=送信 を除去
      ta.addEventListener("input",function(){grow(ta);});
      ta.addEventListener("keydown",function(e){ if(e.key==="Enter"&&(e.ctrlKey||e.metaKey)){ e.preventDefault(); koeSend(); } });
    }
    var send=document.getElementById("chatSendBtn");
    if(send&&!send.__koeBound){ send.__koeBound=true; var c=send.cloneNode(true); send.parentNode.replaceChild(c,send); c.addEventListener("click",koeSend); }
    var pick=document.getElementById("chatImageBtn");
    if(pick&&!pick.__koeBound){ pick.__koeBound=true; pick.addEventListener("click",function(){ var fi=document.getElementById("chatImageInput"); if(fi)fi.click(); }); }
    var fi=document.getElementById("chatImageInput");
    if(fi&&!fi.__koeBound){ fi.__koeBound=true; fi.addEventListener("change",function(){ var f=fi.files&&fi.files[0]; if(!f)return; var rd=new FileReader(); rd.onload=function(){ setPreview(rd.result,f.name); }; rd.readAsDataURL(f); fi.value=""; }); }
    var clr=document.getElementById("chatImageClear");
    if(clr&&!clr.__koeBound){ clr.__koeBound=true; clr.addEventListener("click",function(){ setPreview(null); }); }
  }
  if(document.readyState==="loading")document.addEventListener("DOMContentLoaded",init);else init();
  setTimeout(init,1200);
})();

/* ---- フォント: 手持ちのファイルから読み込み(FontFace) + 起動時復元 ---- */
(function(){
  var FKEY="koe_font_file",FNAME="koe_font_file_name";
  function applyUserFont(dataUrl){
    try{
      var ff=new FontFace("KoeUserFont","url("+dataUrl+")");
      ff.load().then(function(loaded){ try{document.fonts.add(loaded); document.body.style.fontFamily="'KoeUserFont', sans-serif";}catch(e){} }).catch(function(){ toast&&toast("フォントを読み込めませんでした","error"); });
    }catch(e){}
  }
  function init(){
    var fi=document.getElementById("fontFileInput");
    if(fi&&!fi.__koeBound){ fi.__koeBound=true; fi.addEventListener("change",function(){
      var f=fi.files&&fi.files[0]; if(!f)return;
      var rd=new FileReader();
      rd.onload=function(){
        applyUserFont(rd.result);
        try{ localStorage.setItem(FKEY,rd.result); localStorage.setItem(FNAME,f.name); toast&&toast("フォントを適用しました: "+f.name); }
        catch(e){ toast&&toast("適用しました(大きすぎて保存はできません。次回起動時は再選択が必要です)"); }
      };
      rd.readAsDataURL(f);
    }); }
    // 名前付きフォントを選んだらファイル指定は解除
    var sel=document.getElementById("fontSelect");
    if(sel&&!sel.__koeFileHook){ sel.__koeFileHook=true; sel.addEventListener("change",function(){ try{localStorage.removeItem(FKEY);localStorage.removeItem(FNAME);}catch(e){} }); }
    // 起動時復元(applyFontの後に上書き)
    try{ var d=localStorage.getItem(FKEY); if(d)applyUserFont(d); }catch(e){}
  }
  if(document.readyState==="loading")document.addEventListener("DOMContentLoaded",init);else init();
  setTimeout(init,1400);
})();

/* ---- 下部ナビ: 長押しでドラッグして並べ替え(iOS風の滑らかさ / FLIP) ---- */
(function(){
  var LONGPRESS=320, MOVE_CANCEL=12;
  function items(rail){ return Array.prototype.slice.call(rail.querySelectorAll(".rail-item")); }
  function persist(rail){
    try{
      var order=items(rail).map(function(el){return el.dataset.view;}).filter(function(v){ return typeof NAV_DEFAULT!=="undefined"?NAV_DEFAULT.indexOf(v)>=0:!!v; });
      if(typeof NAV_ORDER_KEY!=="undefined") localStorage.setItem(NAV_ORDER_KEY,JSON.stringify(order));
      if(window.applyNavOrder) applyNavOrder();
    }catch(e){}
  }
  function flip(rail,mover){
    // FLIP: 兄弟要素を滑らかに移動
    var els=items(rail).filter(function(e){return e!==mover;});
    var first={}; els.forEach(function(e){ first[e.dataset.view]=e.getBoundingClientRect(); });
    return function(doMove){
      doMove();
      els.forEach(function(e){
        var f=first[e.dataset.view], l=e.getBoundingClientRect();
        var dx=f.left-l.left, dy=f.top-l.top;
        if(dx||dy){ e.style.transition="none"; e.style.transform="translate("+dx+"px,"+dy+"px)";
          requestAnimationFrame(function(){ e.style.transition=""; e.style.transform=""; });
        }
      });
    };
  }
  function attach(rail){
    if(rail.__koeDragInit)return; rail.__koeDragInit=true;
    var vertical=true; // 既定は縦(デスクトップのrail)。実際はドラッグ開始時に判定
    items(rail).forEach(function(item){
      var pressTimer=null,dragging=false,startX=0,startY=0,justDragged=false,origTransition="";
      item.addEventListener("pointerdown",function(e){
        if(e.button!=null&&e.button!==0)return;
        startX=e.clientX; startY=e.clientY; dragging=false;
        pressTimer=setTimeout(function(){
          dragging=true;
          try{item.setPointerCapture(e.pointerId);}catch(_){}
          // 軸判定
          var its=items(rail);
          if(its.length>=2){ var a=its[0].getBoundingClientRect(),b=its[1].getBoundingClientRect(); vertical=Math.abs(b.top-a.top)>=Math.abs(b.left-a.left); }
          rail.classList.add("koe-reordering"); item.classList.add("koe-dragging");
          try{ if(window.haptic)haptic(15);}catch(_){}
        },LONGPRESS);
      });
      item.addEventListener("pointermove",function(e){
        if(!dragging){
          if(Math.abs(e.clientX-startX)>MOVE_CANCEL||Math.abs(e.clientY-startY)>MOVE_CANCEL){ clearTimeout(pressTimer); }
          return;
        }
        e.preventDefault();
        var d=vertical?(e.clientY-startY):(e.clientX-startX);
        item.style.transform="scale(1.16) translate("+(vertical?0:d)+"px,"+(vertical?d:0)+"px)";
        // 入れ替え判定: ポインタ位置に最も近い兄弟の中心を越えたら移動
        var its=items(rail);
        for(var i=0;i<its.length;i++){
          var el=its[i]; if(el===item)continue;
          var r=el.getBoundingClientRect();
          var center=vertical?(r.top+r.height/2):(r.left+r.width/2);
          var pos=vertical?e.clientY:e.clientX;
          var cur=vertical?item.getBoundingClientRect().top:item.getBoundingClientRect().left;
          var before=cur<(vertical?r.top:r.left);
          if((before&&pos>center)||(!before&&pos<center)){
            var run=flip(rail,item);
            run(function(){ if(before)el.parentNode.insertBefore(item,el.nextSibling); else el.parentNode.insertBefore(item,el); });
            // ドラッグ中の指追従をリセット(移動後の新しい基準に)
            startX=e.clientX; startY=e.clientY; item.style.transform="scale(1.16)";
            break;
          }
        }
      });
      function end(e){
        clearTimeout(pressTimer);
        if(!dragging){ return; }
        dragging=false; justDragged=true; setTimeout(function(){justDragged=false;},350);
        item.classList.remove("koe-dragging"); rail.classList.remove("koe-reordering");
        item.style.transition=""; item.style.transform="";
        try{item.releasePointerCapture(e.pointerId);}catch(_){}
        persist(rail);
      }
      item.addEventListener("pointerup",end);
      item.addEventListener("pointercancel",end);
      // ドラッグ直後のクリック(タブ切替)を無効化
      item.addEventListener("click",function(e){ if(justDragged){ e.preventDefault(); e.stopImmediatePropagation(); } },true);
    });
  }
  function init(){ var rail=document.querySelector(".rail"); if(rail)attach(rail); }
  if(document.readyState==="loading")document.addEventListener("DOMContentLoaded",init);else init();
  setTimeout(init,1500);
})();

/* ============================================================
   v57 応援通話(1対1)の作り込み
   ============================================================ */
(function(){
  /* --- 発信前にコイン消費の確認 --- */
  function wrapConfirm(){
    if(!window.requestCheeringCall || window.__koeCheerConfirmWrap) return;
    window.__koeCheerConfirmWrap=true;
    var orig=window.requestCheeringCall;
    window.requestCheeringCall=async function(index){
      try{
        if(typeof cheeringCallActive!=="undefined" && cheeringCallActive) return;
        var rcv=(window._cheeringReceivers||[])[index];
        var label=rcv?(rcv.name||("user "+rcv.user_id)):"この相手";
        var ok=await showConfirmModal("「"+label+"」に応援通話を発信します。\nコインを消費する有料機能です。よろしいですか?");
        if(!ok) return;
        try{ if(window.haptic)haptic(10); }catch(e){}
      }catch(e){}
      return orig(index);
    };
  }

  /* --- 通話確立後: 経過時間つきの通話パネル + 終了ボタン --- */
  window.__koeCheerConnected=function(target,channel,name){
    window.__koeCheeringActive={target:target,channel:channel,name:name,start:Date.now()};
    var el=document.getElementById("cheeringStatusLine");
    if(!el){ var cl=document.getElementById("cheeringList"); if(cl){ el=document.createElement("div"); el.id="cheeringStatusLine"; el.className="empty-msg"; el.style.padding="8px 0"; cl.insertAdjacentElement("beforebegin",el);} }
    if(!el) return;
    function fmt(ms){var s=Math.floor(ms/1000);var m=Math.floor(s/60);s=s%60;return m+":"+(s<10?"0":"")+s;}
    el.innerHTML='<div class="cheer-call-panel"><div class="cheer-call-dot"></div><div class="cheer-call-name">'+escapeHtml(name||("ID:"+target))+' と通話中</div><div class="cheer-call-timer" id="cheerCallTimer">0:00</div><button class="btn-danger" style="width:auto;" onclick="koeEndCheeringCall()">通話を終了</button></div>';
    if(window.__cheerTimer)clearInterval(window.__cheerTimer);
    window.__cheerTimer=setInterval(function(){ var a=window.__koeCheeringActive; var t=document.getElementById("cheerCallTimer"); if(!a||!t){clearInterval(window.__cheerTimer);return;} t.textContent=fmt(Date.now()-a.start); },1000);
    try{ if(window.sfx)sfx("success"); }catch(e){}
  };

  window.koeEndCheeringCall=async function(){
    var a=window.__koeCheeringActive;
    if(window.__cheerTimer)clearInterval(window.__cheerTimer);
    try{ if(window.cheeringDisconnectNow) await cheeringDisconnectNow(); }catch(e){}
    try{ if(typeof cheeringCallActive!=="undefined") cheeringCallActive=false; }catch(e){}
    var el=document.getElementById("cheeringStatusLine"); if(el)el.innerHTML="通話を終了しました";
    if(a&&a.target && window.openCheeringRating) openCheeringRating(a.target,a.name);
  };

  /* --- 通話後の評価(星+コメント) rate_cheering_call --- */
  window.__cheerRating={target:null,value:0};
  function renderCheerStars(v){
    var box=document.getElementById("cheeringRatingStars"); if(!box)return;
    var h="";
    for(var i=1;i<=5;i++){ h+='<span data-v="'+i+'" style="cursor:pointer;padding:0 3px;color:'+(i<=v?"#F5C518":"var(--text-muted,#888)")+';">'+(i<=v?"★":"☆")+'</span>'; }
    box.innerHTML=h;
    Array.prototype.forEach.call(box.querySelectorAll("span"),function(sp){ sp.addEventListener("click",function(){ window.__cheerRating.value=parseInt(sp.dataset.v,10); renderCheerStars(window.__cheerRating.value); try{if(window.haptic)haptic(6);}catch(e){} }); });
  }
  window.openCheeringRating=function(target,name){
    window.__cheerRating={target:target,value:0};
    var who=document.getElementById("cheeringRatingWho"); if(who)who.textContent=(name?("「"+name+"」"):"相手")+"との通話はどうでしたか?";
    var c=document.getElementById("cheeringRatingComment"); if(c)c.value="";
    renderCheerStars(0);
    var m=document.getElementById("cheeringRatingModal"); if(m)m.style.display="flex";
  };
  function closeRating(){ var m=document.getElementById("cheeringRatingModal"); if(m)m.style.display="none"; }
  window.__koeCloseCheerRating=closeRating;
  function bindRating(){
    var sb=document.getElementById("cheeringRatingSubmit");
    if(sb&&!sb.__koeBound){ sb.__koeBound=true; sb.addEventListener("click",async function(){
      var r=window.__cheerRating;
      if(!r||!r.target){ closeRating(); return; }
      if(!r.value){ try{toast("星を選んでください","error");}catch(e){} return; }
      var cm=document.getElementById("cheeringRatingComment"); var comment=cm?cm.value:"";
      sb.disabled=true;
      try{
        var res=await callApi("rate_cheering_call", String(r.target), String(r.value), comment||"");
        if(res&&res.ok){ try{toast("評価を送信しました");}catch(e){} }
        else { try{toast("評価の送信に失敗しました","error");}catch(e){} }
      }catch(e){ try{toast("評価の送信に失敗しました","error");}catch(_){} }
      sb.disabled=false; closeRating();
    }); }
  }

  function init(){ wrapConfirm(); bindRating(); }
  if(document.readyState==="loading")document.addEventListener("DOMContentLoaded",init);else init();
  setTimeout(init,1500);
})();

/* ==== v60: 新規実装した公式機能のフロント配線（データ・履歴 / アカウント操作） ==== */
(function(){
  function fieldsOf(o){
    if(o==null) return '';
    if(typeof o!=='object') return '<div class="card-name">'+escapeHtml(String(o))+'</div>';
    var title=o.name||o.title||o.description||o.text||o.message||o.product_id||o.sku||o.community_name||('ID:'+(o.id!=null?o.id:''));
    var sub=[];
    ['amount','coin','coins','coin_amount','price','point','points','count','status','created_at','date','expired_at','expiration_date','period','duration'].forEach(function(k){
      if(o[k]!=null&&o[k]!=='') sub.push(k+': '+o[k]);
    });
    return '<div class="card-name">'+escapeHtml(String(title)).slice(0,140)+'</div>'+(sub.length?'<div class="card-sub">'+escapeHtml(sub.join('  ·  ')).slice(0,220)+'</div>':'');
  }
  async function loadData(cmd,key,title){
    var m=document.getElementById('koeDataModal'),list=document.getElementById('koeDataList'),t=document.getElementById('koeDataTitle');
    if(t)t.textContent=title; if(list)list.innerHTML='<div class="empty-msg">読み込み中...</div>'; if(m)m.style.display='flex';
    try{
      var r=await callApi(cmd,'1');
      if(!r||!r.ok){ list.innerHTML='<div class="empty-msg">取得できませんでした<br><small style="opacity:.5;word-break:break-all;">'+escapeHtml(JSON.stringify((r&&(r.raw||r.error||r.status))||'').slice(0,180))+'</small></div>'; return; }
      var arr=(r[key]||r.posts||r.items||r.recordings||r.histories||r.menus||r.communities||r.coin_packs||r.data||[]);
      if(!arr.length){ list.innerHTML='<div class="empty-msg">データがありません</div>'; return; }
      list.innerHTML=arr.map(function(o){ return '<div class="card">'+fieldsOf(o)+'</div>'; }).join('');
    }catch(e){ list.innerHTML='<div class="empty-msg">エラー</div>'; }
  }
  function init(){
    Array.prototype.forEach.call(document.querySelectorAll('.koe-data-btn'),function(b){
      if(b.__koeBound)return; b.__koeBound=true;
      b.addEventListener('click',function(){ loadData(b.dataset.cmd,b.dataset.key,b.dataset.title); });
    });
    var rs=document.getElementById('koeResetStatusBtn');
    if(rs&&!rs.__koeBound){ rs.__koeBound=true; rs.addEventListener('click',async function(){
      rs.disabled=true; try{ var r=await callApi('reset_user_status'); if(window.toast)toast(r&&r.ok?'ステータスをリセットしました':'失敗しました',r&&r.ok?undefined:'error'); }catch(e){} rs.disabled=false;
    }); }
    function delAll(kind,label){ return async function(){
      var ok=await showConfirmModal(label+'を全部削除します。取り消せません。本当によろしいですか?');
      if(!ok)return;
      try{ var r=await callApi('delete_all_posts',kind); if(window.toast)toast(r&&r.ok?(label+'を削除しました'):'削除に失敗しました',r&&r.ok?undefined:'error'); }catch(e){}
    };}
    var dt=document.getElementById('koeDeleteAllTimeline'); if(dt&&!dt.__koeBound){dt.__koeBound=true; dt.addEventListener('click',delAll('timeline','タイムライン投稿'));}
    var df=document.getElementById('koeDeleteAllFeed'); if(df&&!df.__koeBound){df.__koeBound=true; df.addEventListener('click',delAll('feed','フィード投稿'));}
  }
  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',init);else init();
  setTimeout(init,1500);
})();

/* ==== v61: 投げ銭を全部開封 ==== */
(function(){
  function bind(){
    var b=document.getElementById("koeOpenAllTippings");
    if(b&&!b.__koeBound){ b.__koeBound=true; b.addEventListener("click",async function(){
      b.disabled=true;
      try{ var r=await callApi("open_all_tippings"); if(window.toast)toast(r&&r.ok?"投げ銭をすべて開封しました":"失敗しました",r&&r.ok?undefined:"error"); }catch(e){}
      b.disabled=false;
    }); }
  }
  if(document.readyState==="loading")document.addEventListener("DOMContentLoaded",bind);else bind();
  setTimeout(bind,1500);
})();

// ==== 応援トークランキング: 種類(声質)・期間の選択に対応 ====
(function(){
  if(window.__koeRankSel) return; window.__koeRankSel=true;
  function optsBar(){ return document.getElementById("rankingOptions"); }
  function curSpec(){
    var r=document.getElementById("rankingRating"), f=document.getElementById("rankingFilter");
    var rv=(r&&r.value)||"1", fv=(f&&f.value)||"2";
    return "rankings:"+rv+":"+fv;
  }
  function wrap(){
    if(typeof window.loadReceivers!=="function"){ setTimeout(wrap,300); return; }
    if(window.__loadReceiversRankWrapped) return; window.__loadReceiversRankWrapped=true;
    var orig=window.loadReceivers;
    window.loadReceivers=function(kind){
      var ob=optsBar();
      if(typeof kind==="string" && kind.indexOf("rankings")===0){
        if(ob) ob.style.display="flex";
        return orig.call(this, curSpec());
      }
      if(ob) ob.style.display="none";
      return orig.call(this, kind);
    };
  }
  function bindSelects(){
    ["rankingRating","rankingFilter"].forEach(function(id){
      var el=document.getElementById(id);
      if(el&&!el.__koeRankBound){ el.__koeRankBound=true; el.addEventListener("change",function(){ if(typeof window.loadReceivers==="function") window.loadReceivers("rankings"); }); }
    });
  }
  function init(){ wrap(); bindSelects(); }
  if(document.readyState==="loading"){document.addEventListener("DOMContentLoaded",init)}else{init()}
  setTimeout(init,1200);
})();

// ==== タイムライン画像を長押しで直接保存 ====
(function(){
  if(window.__koeImgSave) return; window.__koeImgSave=true;
  function canSave(){ try{ return !!(window.AndroidApi && window.AndroidApi.saveImage); }catch(e){ return false; } }
  function doSave(src){
    if(!src||!canSave()) return;
    try{ if(typeof haptic==="function") haptic(14); }catch(e){}
    try{ window.AndroidApi.saveImage(src); if(typeof toast==="function") toast("画像を保存中…"); }catch(e){}
  }
  var timer=null, longPressed=false;
  function isPostImg(t){ return t && t.tagName==="IMG" && (t.classList.contains("post-image")||t.classList.contains("pd-post-image")||(t.id==="lightboxImg")); }
  document.addEventListener("touchstart",function(e){
    var t=e.target; if(!isPostImg(t)) return;
    longPressed=false;
    timer=setTimeout(function(){ longPressed=true; doSave(t.src); }, 550);
  },{passive:true});
  document.addEventListener("touchend",function(e){ if(timer){clearTimeout(timer);timer=null;} if(longPressed){ try{e.preventDefault();}catch(_){} } },{passive:false});
  document.addEventListener("touchmove",function(){ if(timer){clearTimeout(timer);timer=null;} },{passive:true});
  // PC/一部端末: 右クリック(長押し相当)でも保存
  document.addEventListener("contextmenu",function(e){ var t=e.target; if(isPostImg(t)&&canSave()){ e.preventDefault(); doSave(t.src); } });
})();

// ==== 共有BANリスト連携の配線 ====
(function(){
  if(window.__koeBanlistWired) return; window.__koeBanlistWired=true;
  window.__koeBannedSet=window.__koeBannedSet||new Set();
  // サーバーURLは固定(変更・表示不可)。ユーザーからは隠す。
  var BANLIST_FIXED_URL="https://redredfast.com";
  function getUrl(){ return BANLIST_FIXED_URL; }
  function setUrl(u){ /* 固定URLのため何もしない */ }
  function T(m,t){ try{ if(typeof toast==="function") toast(m,t); }catch(e){} }
  function esc(s){ try{return escapeHtml(String(s==null?"":s))}catch(e){return ""} }

  var client=null;
  function ensureClient(){
    if(!window.createBanlistClient) return null;
    if(client){ client.setBaseUrl(getUrl()); return client; }
    client=window.createBanlistClient({
      baseUrl:getUrl(),
      blockFn:function(uid){ try{window.__koeBannedSet.add(String(uid))}catch(e){} }, /* 実ブロックは同意制のゆっくりブロックエンジン(__koeBlockEngine)が担当。ここはローカル印だけ(一斉ブロック回避) */
      isBlocked:function(uid){ try{return window.__koeBannedSet.has(String(uid))}catch(e){return false} },
      loadEtag:function(){ try{return localStorage.getItem("koe_banlist_etag")||""}catch(e){return ""} },
      saveEtag:function(e){ try{localStorage.setItem("koe_banlist_etag",e||"")}catch(x){} },
      onLog:function(m){ try{var el=document.getElementById("banlistStatus"); var sec=document.getElementById("secModeration"); if(el&&sec&&sec.open) el.textContent=m;}catch(e){} },
      intervalMs:900000
    });
    window.__koeBanlist=client;
    return client;
  }
  function startIfConfigured(){ var c=ensureClient(); if(c&&getUrl()&&(function(){try{return localStorage.getItem("koe_block_consent")==="yes"}catch(e){return false}})()) c.start(); }
  window.__koeStartBanlist=startIfConfigured;

  function banlistRowsHtml(list){
    if(!list||!list.length) return '<div class="empty-msg" style="padding:6px 0;">BANリストは空、または未取得です</div>';
    return list.map(function(b){
      var since=""; try{ if(b.since) since=new Date(b.since*1000).toLocaleDateString(); }catch(e){}
      return '<div class="card" style="cursor:default;display:block;"><div class="card-body"><div class="card-name">ID:'+esc(b.uid)+' <span class="uid-tag">'+esc(b.code||"")+'</span></div><div class="card-sub" style="opacity:.8;">'+esc(b.reason||"")+(since?" ・ "+since:"")+'</div><button class="btn-secondary koe-appeal" data-uid="'+esc(b.uid)+'" style="width:auto;margin-top:6px;padding:3px 10px;font-size:12px;">異議申し立て</button></div></div>';
    }).join("");
  }
  function wireBanlistAppeal(root){
    Array.prototype.forEach.call(root.querySelectorAll(".koe-appeal"),function(bt){
      bt.addEventListener("click",async function(){
        var uid=bt.getAttribute("data-uid");
        var msg=await showInputModal("異議申し立て","このIDがbot/違反ではない理由");
        if(!msg) return;
        var r=await callApi("moderation_appeal",getUrl(),uid,msg);
        if(r&&r.ok){ T(r.duplicate?"既に申請済みです":"異議を送信しました"); }
        else if(r&&r.error==="not_banned"){ T("この相手は現在BAN対象ではないため、異議申し立ては不要です","error"); }
        else if(r&&r.error==="rate_limited"){ T("送信が多すぎます。時間をおいてください","error"); }
        else { T("送信に失敗しました","error"); }
      });
    });
  }
  async function showBanlistModal(){
    var c=ensureClient();
    var m=document.createElement("div"); m.className="modal"; m.style.display="flex";
    m.innerHTML='<div class="modal-content"><div class="modal-header"><span>共有BANリスト</span><button class="modal-close koe-bl-x">✕</button></div><div class="modal-body"><div class="card-sub" style="opacity:.85;margin-bottom:8px;">コミュニティで承認された迷惑ユーザー(bot/スパム等)の一覧です。誤りがあれば「異議申し立て」できます。</div><div class="koe-bl-body"><div class="empty-msg" style="padding:6px 0;">読み込み中…</div></div></div></div>';
    document.body.appendChild(m);
    function close(){ try{m.remove()}catch(e){} }
    m.querySelector(".koe-bl-x").addEventListener("click",close);
    m.addEventListener("click",function(e){ if(e.target===m) close(); });
    var body=m.querySelector(".koe-bl-body");
    if(c){ try{ await c.sync(); }catch(e){} }
    var list=(c&&c.getList)?c.getList():[];
    body.innerHTML=banlistRowsHtml(list);
    wireBanlistAppeal(body);
  }

  function bindSettings(){
    // サーバーURLは固定・非表示。入力欄と保存ボタンは廃止済み。
    var view=document.getElementById("banlistViewBtn");
    if(view && view.__b!==1){ view.__b=1; view.addEventListener("click",function(){ try{ window.koeOpenExternal("https://redredfast.com/api/bl/view"); }catch(e){} }); }
  }

  // 証拠画像を選んで圧縮(最大1280px, JPEG0.7)しbase64で返す
  function koePickImage(){
    return new Promise(function(resolve){
      var inp=document.createElement("input"); inp.type="file"; inp.accept="image/*";
      inp.onchange=function(){ var f=inp.files&&inp.files[0]; if(!f){resolve("");return;}
        var rd=new FileReader(); rd.onload=function(){ var img=new Image(); img.onload=function(){
          var mx=1280,w=img.width,h=img.height; if(w>mx||h>mx){var s=mx/Math.max(w,h); w=Math.round(w*s); h=Math.round(h*s);}
          var cv=document.createElement("canvas"); cv.width=w; cv.height=h; cv.getContext("2d").drawImage(img,0,0,w,h);
          try{resolve(cv.toDataURL("image/jpeg",0.7))}catch(e){resolve("")};
        }; img.onerror=function(){resolve("")}; img.src=rd.result; };
        rd.onerror=function(){resolve("")}; rd.readAsDataURL(f);
      };
      inp.click();
    });
  }
  function ytOk(u){ return !u || /^https?:\/\/(www\.)?(youtube\.com|youtu\.be)\//i.test(u); }
  try{ window.koePickImage=koePickImage; window.__koeYtOk=ytOk; }catch(e){}
  // 通報: 理由＋説明＋スクショ＋YouTube＋任意ID
  function reasonPicker(){
    return new Promise(function(resolve){
      var codes=window.KOE_REASON_CODES||{spam:"スパム",scam:"詐欺・宣伝",bot:"bot/自動化",nsfw:"不適切コンテンツ",harass:"嫌がらせ",other:"その他"};
      var sel=null, img="";
      var m=document.createElement("div"); m.className="modal"; m.style.display="flex";
      var fld="width:100%;box-sizing:border-box;padding:8px;border-radius:8px;background:var(--bg-input,#1c1c1c);color:var(--text-normal,#dbdee1);border:1px solid var(--border);";
      var opts=Object.keys(codes).map(function(k){return '<button type="button" class="btn-secondary koe-rc" data-code="'+k+'" style="width:auto;margin:3px 4px 3px 0;padding:5px 10px;">'+codes[k]+'</button>';}).join("");
      m.innerHTML='<div class="modal-content small"><div class="modal-header"><span>共有BANリストに報告</span><button class="modal-close koe-rc-x">✕</button></div><div class="modal-body">'
        +'<p class="page-desc">悪質なユーザー(bot・詐欺・嫌がらせ等)を共有BANリストに報告します。通報者があなたであることは公開されません。</p>'
        +'<label class="field-label">理由</label><div style="display:flex;flex-wrap:wrap;">'+opts+'</div>'
        +'<label class="field-label" style="margin-top:8px;">状況の説明(任意)</label><textarea class="koe-rc-detail" rows="2" placeholder="何をされたか等" style="'+fld+'"></textarea>'
        +'<label class="field-label" style="margin-top:8px;">証拠スクショ(任意)</label><div style="display:flex;gap:8px;align-items:center;"><button type="button" class="btn-secondary koe-rc-img" style="width:auto;">画像を選ぶ</button><span class="koe-rc-imgst" style="font-size:12px;opacity:.8;">なし</span></div>'
        +'<label class="field-label" style="margin-top:8px;">証拠のYouTube限定公開リンク(任意)</label><input class="koe-rc-url" type="text" inputmode="url" placeholder="https://youtu.be/..." style="'+fld+'">'
        +'<label class="field-label" style="margin-top:8px;">返信が欲しい方は声ともID(任意)</label><input class="koe-rc-contact" type="text" placeholder="作者が個別チャットで連絡する場合があります" style="'+fld+'">'
        +'<p class="page-desc" style="margin-top:8px;font-size:11px;opacity:.75;">※テキストだけの報告は確認が難しく後回し/受付不可の場合があります。証拠(スクショ/YouTube限定公開リンク)があると早く反映されます。違法・個人情報の暴露・未成年関連の証拠は受け付けません。すべてに個別対応・返信はできません。</p>'
        +'<div style="display:flex;gap:8px;margin-top:10px;"><button type="button" class="btn-primary koe-rc-send" style="width:auto;">報告を送信</button><button type="button" class="btn-secondary koe-rc-cancel" style="width:auto;">キャンセル</button></div>'
        +'</div></div>';
      document.body.appendChild(m);
      function done(v){ try{m.remove()}catch(e){} resolve(v); }
      m.querySelector(".koe-rc-x").addEventListener("click",function(){done(null)});
      m.querySelector(".koe-rc-cancel").addEventListener("click",function(){done(null)});
      m.addEventListener("click",function(e){ if(e.target===m) done(null); });
      Array.prototype.forEach.call(m.querySelectorAll(".koe-rc"),function(b){ b.addEventListener("click",function(){ sel=b.getAttribute("data-code"); Array.prototype.forEach.call(m.querySelectorAll(".koe-rc"),function(x){x.classList.remove("active")}); b.classList.add("active"); }); });
      m.querySelector(".koe-rc-img").addEventListener("click",async function(){ var st=m.querySelector(".koe-rc-imgst"); st.textContent="読み込み中…"; img=await koePickImage(); st.textContent=img?"添付済み":"なし"; });
      m.querySelector(".koe-rc-send").addEventListener("click",function(){
        if(!sel){ try{toast("理由を選んでください","error")}catch(e){} return; }
        var d=m.querySelector(".koe-rc-detail"), u=m.querySelector(".koe-rc-url"), c=m.querySelector(".koe-rc-contact");
        var url=(u&&u.value||"").trim();
        if(!ytOk(url)){ try{toast("YouTubeのリンクを入れてください","error")}catch(e){} return; }
        done({code:sel, detail:(d&&d.value||"").trim(), image:img, url:url, contact:(c&&c.value||"").trim()});
      });
    });
  }
  async function doProfileReport(ev){
    try{ if(ev){ev.stopImmediatePropagation&&ev.stopImmediatePropagation(); ev.preventDefault&&ev.preventDefault();} }catch(e){}
    var st=window.profileViewFollowState; if(!st){ return; }
    var uid=st.userId;
    var url=getUrl();
    if(!url){
      // URL未設定 → 従来のkoetomo通報にフォールバック
      var reason=await showInputModal("通報理由を入力","このユーザーを通報します");
      if(!reason) return;
      var r0=await callApi("report_timeline_post",uid,reason);
      T((r0&&r0.ok)?"通報しました":"通報に失敗しました",(r0&&r0.ok)?undefined:"error");
      return;
    }
    var pick=await reasonPicker(); if(!pick) return;
    var r=await callApi("moderation_report",url,String(uid),pick.code,pick.detail||"","",pick.image||"",pick.url||"",pick.contact||"");
    if(r&&r.ok){ T(r.duplicate?"既に報告済みです":"報告しました。承認されるとBANリストに反映されます"); }
    else if(r&&r.error==="rate_limited"){ T("通報が多すぎます。時間をおいてください","error"); }
    else if(r&&r.error==="cannot_report_self"){ T("自分自身は通報できません","error"); }
    else { T("通報に失敗しました","error"); }
  }
  function bindReport(){
    var btn=document.getElementById("profileViewReportBtn");
    if(btn && btn.__modb!==1){ btn.__modb=1; btn.addEventListener("click",doProfileReport,true); } // capture段で先取り
  }

  // プロフィールメニューの「ブラックリスト申請」ボタン専用。通報(doProfileReport)と同じ
  // moderation_report APIを使い、承認されると共有BANリストに反映される(既存のBANリスト機能を再利用)。
  window.__koeSubmitBlacklistReport=async function(uid){
    try{
      var url=getUrl();
      if(!url){ T("ブラックリスト機能が利用できません","error"); return; }
      var pick=await reasonPicker(); if(!pick) return;
      var r=await callApi("moderation_report",url,String(uid),pick.code,pick.detail||"","",pick.image||"",pick.url||"",pick.contact||"");
      if(r&&r.ok){ T(r.duplicate?"既に申請済みです":"ブラックリスト申請を送信しました。承認されるとBANリストに反映されます"); }
      else if(r&&r.error==="rate_limited"){ T("申請が多すぎます。時間をおいてください","error"); }
      else if(r&&r.error==="cannot_report_self"){ T("自分自身は申請できません","error"); }
      else { T("ブラックリスト申請に失敗しました","error"); }
    }catch(e){ T("ブラックリスト申請に失敗しました","error"); }
  };

  function init(){ bindSettings(); bindReport(); startIfConfigured(); }
  if(document.readyState==="loading"){document.addEventListener("DOMContentLoaded",init)}else{init()}
  window.addEventListener("pywebviewready",function(){ setTimeout(function(){ bindReport(); startIfConfigured(); },1500); });
  document.addEventListener("click",function(e){ try{ if(e.target&&e.target.closest&&e.target.closest("#secModeration")) setTimeout(bindSettings,60); }catch(x){} });
})();

// ==== フォロー/ミュート/ブロック操作後、開いている一覧を即座に反映(取り残された行を消す) ====
(function(){
  function refreshOpenLists(){
    try{
      var flm=document.getElementById("followListModal");
      if(flm&&flm.style.display!=="none"&&window.__koeLastFollowList){
        openFollowList(window.__koeLastFollowList.userId,window.__koeLastFollowList.kind);
      }
    }catch(e){}
    try{
      var bl=document.getElementById("blockedList");
      if(bl&&bl.offsetParent!==null&&typeof loadBlockedUsers==="function"){ loadBlockedUsers(); }
    }catch(e){}
    try{
      var ml=document.getElementById("mutedUsersList")||document.getElementById("mutedList");
      if(ml&&ml.offsetParent!==null&&typeof loadMutedUsers==="function"){ loadMutedUsers(); }
    }catch(e){}
  }
  window.addEventListener("koe:block-changed",refreshOpenLists);
  window.addEventListener("koe:follow-changed",refreshOpenLists);
})();

// ==== 共有BANリスト: 声とも本体での実ブロック(ゆっくり・前面のみ・冪等)＋同意/進捗/解除＋異議申し立て ====
(function(){
  if(window.__koeBlockEngine) return;
  var DONE="koe_block_done", EXEMPT="koe_block_exempt", CONSENT="koe_block_consent", SEED="koe_block_seeded";
  var SESSION_CAP=25, MIN_MS=5000, MAX_MS=15000;
  /* 外部サーバー依存への安全弁: 1日あたりの上限と、リストが急増した時の一時停止(確認制) */
  var DAILY_CAP=40, DAY_KEY="koe_block_day", DAY_CNT="koe_block_daycnt", LAST_N="koe_block_lastn", PAUSE="koe_block_pause";
  function dayStr(){ var d=new Date(); return d.getFullYear()+"-"+(d.getMonth()+1)+"-"+d.getDate(); }
  function dayCount(){ try{ if(localStorage.getItem(DAY_KEY)!==dayStr()){ localStorage.setItem(DAY_KEY,dayStr()); localStorage.setItem(DAY_CNT,"0"); } return parseInt(localStorage.getItem(DAY_CNT)||"0",10)||0; }catch(e){ return 0 } }
  function bumpDay(){ try{ localStorage.setItem(DAY_CNT,String(dayCount()+1)); }catch(e){} }
  function anomaly(){ try{ var n=bannedUids().length; var prev=parseInt(localStorage.getItem(LAST_N)||"-1",10); if(prev>=0 && (n>prev*2+20)){ localStorage.setItem(PAUSE,"1"); T("共有BANリストが急増したため自動ブロックを一時停止しました(設定から再開できます)","error"); return true; } if(localStorage.getItem(PAUSE)==="1") return true; localStorage.setItem(LAST_N,String(n)); return false; }catch(e){ return false } }
  window.__koeBlockResume=function(){ try{ localStorage.removeItem(PAUSE); var n=bannedUids().length; localStorage.setItem(LAST_N,String(n)); }catch(e){} };
  /* ブロック済み/除外の記録は localStorage に加えてネイティブ側(暗号化ストア)にも複製する。
     WebView のストレージが消えても同じ相手を毎回ブロックし直さないため(冪等性の担保) */
  function loadSet(k){ var st=new Set(); try{ JSON.parse(localStorage.getItem(k)||"[]").forEach(function(x){st.add(String(x))}); }catch(e){}
    try{ var a=window.AndroidApi; if(a&&a.secureLoad){ var j=a.secureLoad(k); if(j){ JSON.parse(j).forEach(function(x){st.add(String(x))}); } } }catch(e){}
    return st; }
  /* 注意: Set は Array.prototype.slice で配列化できない(常に空になる) → Array.from を使う */
  function saveSet(k,s){ var arr=Array.from(s); try{localStorage.setItem(k,JSON.stringify(arr))}catch(e){} try{ var a=window.AndroidApi; if(a&&a.secureSave) a.secureSave(k,JSON.stringify(arr)); }catch(e){} }
  function getC(){ try{return localStorage.getItem(CONSENT)||""}catch(e){return ""} }
  function setC(v){ try{localStorage.setItem(CONSENT,v)}catch(e){} }
  function ytOk(u){ if(window.__koeYtOk) return window.__koeYtOk(u); return !u||/^https?:\/\/(www\.)?(youtube\.com|youtu\.be)\//i.test(u); }
  function pickImage(){ if(window.koePickImage) return window.koePickImage(); return Promise.resolve(""); }
  var doneSet=loadSet(DONE), exemptSet=loadSet(EXEMPT);
  var sessionCount=0, timer=null, running=false, last=null, backoff=false;

  function T(m,t){ try{ if(typeof toast==="function") toast(m,t); }catch(e){} }
  function esc(s){ try{return escapeHtml(String(s==null?"":s))}catch(e){return String(s==null?"":s)} }
  function bannedList(){ try{ var c=window.__koeBanlist; if(c&&c.getList) return c.getList(); }catch(e){} return []; }
  function bannedUids(){ return bannedList().map(function(b){return String(b.uid)}).filter(function(u){return u&&u!=="null"&&u!=="undefined"}); }
  function queueUids(){ return bannedUids().filter(function(u){ return !doneSet.has(u) && !exemptSet.has(u); }); }
  function fg(){ try{return document.visibilityState==="visible" && document.hasFocus();}catch(e){return true} }
  function rnd(a,b){ return a+Math.floor(Math.random()*(b-a)); }

  async function seed(){
    try{ if(localStorage.getItem(SEED)==="1") return; }catch(e){}
    try{ var r=await callApi("get_block_list"); if(r&&r.ok&&r.users){ r.users.forEach(function(u){ var id=String(u.user_id!=null?u.user_id:(u.id!=null?u.id:"")); if(id&&id!=="0"&&id!=="") doneSet.add(id); }); saveSet(DONE,doneSet); } }catch(e){}
    try{ localStorage.setItem(SEED,"1"); }catch(e){}
  }

  function sched(){ if(timer||backoff) return; timer=setTimeout(tick, rnd(MIN_MS,MAX_MS)); }
  function halt(){ if(timer){clearTimeout(timer); timer=null;} }

  async function tick(){
    timer=null;
    if(getC()!=="yes") return;
    if(!fg()) return;
    if(sessionCount>=SESSION_CAP){ render(); return; }
    if(dayCount()>=DAILY_CAP){ render(); return; }
    if(anomaly()){ render(); return; }
    var q=queueUids();
    if(!q.length){ render(); return; }
    var uid=q[Math.floor(Math.random()*q.length)];
    try{
      var r=await callApi("block_user", uid);
      try{ if(window.AndroidApi&&window.AndroidApi.log) window.AndroidApi.log("[BLOCK] auto uid="+uid+" ok="+!!(r&&r.ok)+" done="+doneSet.size+" queue="+q.length); }catch(e){}
      if(r&&r.ok){ doneSet.add(uid); saveSet(DONE,doneSet); sessionCount++; bumpDay(); last=uid; render(); }
      else if(r&&r.status===429){ backoff=true; setTimeout(function(){backoff=false; sched();},60000); render(); return; }
      else { doneSet.add(uid); saveSet(DONE,doneSet); }
    }catch(e){}
    sched();
  }

  function start(){ if(running) return; running=true;
    try{ var lsN=0,lsB=0; try{ for(var i=0;i<localStorage.length;i++){ var kk=localStorage.key(i); lsN++; lsB+=(localStorage.getItem(kk)||"").length; } }catch(e){}
      var nat="-"; try{ var a=window.AndroidApi; if(a&&a.secureLoad){ var j=a.secureLoad(DONE); nat=j?JSON.parse(j).length:0; } }catch(e){ nat="err" }
      var lsd="-"; try{ lsd=JSON.parse(localStorage.getItem(DONE)||"[]").length; }catch(e){ lsd="err" }
      if(window.AndroidApi&&window.AndroidApi.log) window.AndroidApi.log("[BLOCK] init consent="+getC()+" done(ls)="+lsd+" done(native)="+nat+" exempt="+exemptSet.size+" ls_keys="+lsN+" ls_chars="+lsB); }catch(e){}
    seed().then(function(){ try{ if(window.__koeStartBanlist) window.__koeStartBanlist(); }catch(e){} sched(); render(); }); }
  function stop(){ running=false; halt(); }

  try{ document.addEventListener("visibilitychange",function(){ if(getC()==="yes"&&fg()) sched(); else halt(); }); }catch(e){}
  try{ window.addEventListener("focus",function(){ if(getC()==="yes") sched(); }); }catch(e){}

  async function unblock(uid){
    uid=String(uid);
    var r=await callApi("unblock_user", uid);
    if(r&&r.ok){ doneSet.delete(uid); exemptSet.add(uid); saveSet(DONE,doneSet); saveSet(EXEMPT,exemptSet); render(); return true; }
    return false;
  }

  function stats(){ var uids=bannedUids(), done=0; uids.forEach(function(u){ if(doneSet.has(u)) done++; }); return {total:uids.length, done:done, pct: uids.length?Math.round(done*100/uids.length):100, remain:queueUids().length}; }

  function render(){
    var st=document.getElementById("banlistStatus"), box=document.getElementById("banlistList");
    var c=getC();
    if(st){
      if(c!=="yes"){ st.textContent = c==="no" ? "自動ブロックはオフです" : "未設定(初回に確認します)"; }
      else { var s2=stats(); st.textContent="共有BANリスト反映中: "+s2.done+" / "+s2.total+" ("+s2.pct+"%)"+(s2.remain?" ・残り"+s2.remain:" ・完了")+(last?" ・直近ID:"+last:""); }
    }
    if(box){
      if(c!=="yes"){ box.innerHTML=""; return; }
      var bu=bannedUids();
      var done=Array.from(doneSet).filter(function(u){return bu.indexOf(u)>=0;});
      var s=stats();
      var bar='<div style="height:8px;border-radius:6px;background:rgba(128,128,128,.25);overflow:hidden;margin:6px 0;"><div style="height:100%;width:'+s.pct+'%;background:var(--accent,#4a90d9);transition:width .3s;"></div></div>';
      var rows=done.slice(0,50).map(function(u){ return '<div class="card" style="display:block;"><div class="card-body" style="display:flex;justify-content:space-between;align-items:center;"><span class="card-name">ID:'+esc(u)+'</span><button class="btn-secondary koe-unblk" data-uid="'+esc(u)+'" style="width:auto;padding:3px 10px;font-size:12px;">解除</button></div></div>'; }).join("");
      box.innerHTML=bar+(done.length?('<div style="font-size:12px;opacity:.8;margin:4px 0;">ブロック済み '+done.length+'件'+(done.length>50?'(先頭50件)':'')+'</div>'+rows):'<div class="empty-msg" style="padding:6px 0;">まだブロックした相手はいません</div>');
      Array.prototype.forEach.call(box.querySelectorAll(".koe-unblk"),function(b){ b.addEventListener("click",async function(){ b.disabled=true; var ok=await unblock(b.getAttribute("data-uid")); T(ok?"解除しました(今後も自動ブロックしません)":"解除に失敗しました",ok?undefined:"error"); }); });
    }
  }

  function showConsent(){
    var m=document.createElement("div"); m.className="modal"; m.style.display="flex";
    var on=getC()==="yes";
    m.innerHTML='<div class="modal-content small"><div class="modal-header"><span>共有BANリストを取得しますか？</span><button class="modal-close koe-cs-x">✕</button></div><div class="modal-body">'
      +'<p class="page-desc">redredfast のブロックサーバーから、コミュニティで承認された迷惑ユーザー(bot・スパム・詐欺など)の一覧を取得します。</p>'
      +'<p class="page-desc"><b>何をするか</b> — 取得した相手をこの端末で非表示にし、さらに声とも本体でも順番にブロックします。</p>'
      +'<p class="page-desc"><b>裏での処理</b> — 一覧は起動時に一括取得(通信は1回)。ブロックは一気にやらず、アプリを開いている間だけ数秒〜十数秒おきにランダムな間隔で1人ずつ実行します。一度ブロックした相手は記録して二度と繰り返しません。閉じたら次回続きから再開します。</p>'
      +'<p class="page-desc" style="color:#e0a030;"><b>懸念・リスク</b> — これはあなたの実アカウントでのブロックで、声とものブロック一覧が実際に書き換わります。短時間の大量ブロックは不審な操作と見なされる可能性があるため、検知されないようランダム間隔・少しずつ・上限付きで行います。共有リストに誤りが混じる可能性もありますが、間違ってブロックした相手はいつでも個別に解除でき(解除した人は再ブロックしません)、処理はいつでも停止できます。</p>'
      +'<div style="display:flex;gap:8px;margin-top:12px;flex-wrap:wrap;"><button type="button" class="btn-primary koe-cs-yes" style="width:auto;">'+(on?'取得を続ける':'はい、始める')+'</button><button type="button" class="btn-secondary koe-cs-no" style="width:auto;">'+(on?'オフにする':'いいえ')+'</button></div>'
      +'</div></div>';
    document.body.appendChild(m);
    function close(){ try{m.remove()}catch(e){} }
    m.querySelector(".koe-cs-x").addEventListener("click",close);
    m.addEventListener("click",function(e){ if(e.target===m) close(); });
    m.querySelector(".koe-cs-yes").addEventListener("click",function(){ setC("yes"); close(); running=false; start(); T("共有BANリストを有効にしました"); });
    m.querySelector(".koe-cs-no").addEventListener("click",function(){ setC("no"); close(); stop(); try{ if(window.__koeBanlist&&window.__koeBanlist.stop) window.__koeBanlist.stop(); }catch(e){} render(); T("オフにしました"); });
  }

  function showAppeal(defId){
    var fld="width:100%;box-sizing:border-box;padding:8px;border-radius:8px;background:var(--bg-input,#1c1c1c);color:var(--text-normal,#dbdee1);border:1px solid var(--border);";
    var img="";
    var m=document.createElement("div"); m.className="modal"; m.style.display="flex";
    m.innerHTML='<div class="modal-content small"><div class="modal-header"><span>BANの異議申し立て</span><button class="modal-close koe-ap-x">✕</button></div><div class="modal-body">'
      +'<p class="page-desc">共有BANリストの登録が誤りだと異議を申し立てます。証拠(スクショやYouTube限定公開リンク)があると確認が早くなります。すべてに個別対応・返信はできません。</p>'
      +'<label class="field-label">対象の声ともID</label><input class="koe-ap-id" type="text" value="'+(defId?esc(defId):"")+'" placeholder="対象ID" style="'+fld+'">'
      +'<label class="field-label" style="margin-top:8px;">理由(テキスト)</label><textarea class="koe-ap-msg" rows="3" placeholder="botや違反ではない理由" style="'+fld+'"></textarea>'
      +'<label class="field-label" style="margin-top:8px;">証拠スクショ(任意)</label><div style="display:flex;gap:8px;align-items:center;"><button type="button" class="btn-secondary koe-ap-img" style="width:auto;">画像を選ぶ</button><span class="koe-ap-imgst" style="font-size:12px;opacity:.8;">なし</span></div>'
      +'<label class="field-label" style="margin-top:8px;">YouTube限定公開リンク(任意)</label><input class="koe-ap-url" type="text" inputmode="url" placeholder="https://youtu.be/..." style="'+fld+'">'
      +'<div style="display:flex;gap:8px;margin-top:10px;"><button type="button" class="btn-primary koe-ap-send" style="width:auto;">送信</button><button type="button" class="btn-secondary koe-ap-cancel" style="width:auto;">キャンセル</button></div>'
      +'</div></div>';
    document.body.appendChild(m);
    function close(){ try{m.remove()}catch(e){} }
    m.querySelector(".koe-ap-x").addEventListener("click",close);
    m.querySelector(".koe-ap-cancel").addEventListener("click",close);
    m.addEventListener("click",function(e){ if(e.target===m) close(); });
    m.querySelector(".koe-ap-img").addEventListener("click",async function(){ var stt=m.querySelector(".koe-ap-imgst"); stt.textContent="読み込み中…"; img=await pickImage(); stt.textContent=img?"添付済み":"なし"; });
    m.querySelector(".koe-ap-send").addEventListener("click",async function(){
      var id=(m.querySelector(".koe-ap-id").value||"").trim();
      var msg=(m.querySelector(".koe-ap-msg").value||"").trim();
      var url=(m.querySelector(".koe-ap-url").value||"").trim();
      if(!id){ T("対象IDを入れてください","error"); return; }
      if(!ytOk(url)){ T("YouTubeのリンクを入れてください","error"); return; }
      var r=await callApi("moderation_appeal", "https://redredfast.com", id, msg, img, url);
      T((r&&r.ok)?(r.duplicate?"既に申請済みです":"異議を送信しました"):"送信に失敗しました",(r&&r.ok)?undefined:"error");
      if(r&&r.ok) close();
    });
  }

  function bind(){
    var cb=document.getElementById("banlistConsentBtn");
    if(cb&&cb.__b!==1){ cb.__b=1; cb.addEventListener("click",showConsent); }
    var ab=document.getElementById("banlistAppealBtn");
    if(ab&&ab.__b!==1){ ab.__b=1; ab.addEventListener("click",function(){ showAppeal(""); }); }
  }

  function boot(){
    bind();
    var c=getC();
    if(c==="yes"){ start(); }
    else if(c===""){ setTimeout(showConsent, 1600); }
    render();
  }
  if(document.readyState==="loading") document.addEventListener("DOMContentLoaded",boot); else setTimeout(boot,900);
  document.addEventListener("click",function(){ setTimeout(function(){ var sec=document.getElementById("secModeration"); if(sec&&sec.open){ bind(); render(); } },150); },true);

  window.__koeBlockEngine={start:start,stop:stop,unblock:unblock,render:render,showConsent:showConsent,showAppeal:showAppeal,stats:stats,consent:getC};
})();

window.openUserSearchModal=function(mode){
  window.__koeUserSearchMode=mode||"profile";
  var m=document.getElementById("userSearchModal");
  if(!m)return;
  var t=document.getElementById("userSearchModalTitle");
  if(t)t.textContent=mode==="chat"?"チャット相手を検索":"ユーザー検索";
  var inp=document.getElementById("userSearchModalInput");
  var res=document.getElementById("userSearchModalResults");
  if(inp)inp.value="";
  if(res)res.innerHTML='<div class="empty-msg">名前を入力して検索してください</div>';
  m.style.display="flex";
  if(inp)setTimeout(function(){inp.focus()},50);
};
async function __koeDoUserSearchModal(){
  var inp=document.getElementById("userSearchModalInput");
  var res=document.getElementById("userSearchModalResults");
  if(!inp||!res)return;
  var name=inp.value.trim();
  if(!name){res.innerHTML='<div class="empty-msg">名前を入力してください</div>';return;}
  res.innerHTML=skeletonCards(3);
  var r=await callApi("search_users",name,"1");
  if(!r||!r.ok){res.innerHTML='<div class="empty-msg">検索に失敗しました</div>';return;}
  if(!r.users||!r.users.length){res.innerHTML='<div class="empty-msg">見つかりませんでした</div>';return;}
  var chatMode=window.__koeUserSearchMode==="chat";
  res.innerHTML=r.users.map(function(u){
    return '<div class="card" data-uid="'+u.user_id+'" data-uname="'+escapeHtml(u.name||("user "+u.user_id))+'" data-uicon="'+escAttr(u.icon_url||"")+'">'+avatarHtml(u.name,u.icon_url)+'<div class="card-body"><div class="card-name">'+escapeHtml(u.name||"user "+u.user_id)+' <span class="uid-tag">ID:'+u.user_id+"</span></div>"+(u.age?'<div class="card-sub">'+escapeHtml(String(u.age))+"歳</div>":"")+"</div></div>";
  }).join("");
  res.onclick=function(e){
    var card=e.target.closest("[data-uid]");
    if(!card)return;
    var uid=card.dataset.uid,uname=card.dataset.uname,uicon=card.dataset.uicon;
    document.getElementById("userSearchModal").style.display="none";
    if(window.__koeUserSearchMode==="chat"){openChat("",uid,uname,uicon);}
    else{viewProfile(uid);}
  };
}
(function(){
  function bind(){
    var sb=document.getElementById("tlSearchBtn");
    if(sb&&!sb.__koeBound){sb.__koeBound=true;sb.addEventListener("click",function(){openUserSearchModal("profile");});}
    var cn=document.getElementById("chatNewBtn");
    if(cn&&!cn.__koeBound){cn.__koeBound=true;cn.addEventListener("click",function(){openUserSearchModal("chat");});}
    var usmc=document.getElementById("userSearchModalClose");
    if(usmc&&!usmc.__koeBound){usmc.__koeBound=true;usmc.addEventListener("click",function(){document.getElementById("userSearchModal").style.display="none";});}
    var usmb=document.getElementById("userSearchModalBtn");
    if(usmb&&!usmb.__koeBound){usmb.__koeBound=true;usmb.addEventListener("click",__koeDoUserSearchModal);}
    var usmi=document.getElementById("userSearchModalInput");
    if(usmi&&!usmi.__koeBound){usmi.__koeBound=true;usmi.addEventListener("keydown",function(e){if(e.key==="Enter")__koeDoUserSearchModal();});}
  }
  if(document.readyState==="loading"){document.addEventListener("DOMContentLoaded",bind)}else{bind()}
  setTimeout(bind,1200);
})();

(function(){
  function openMypageSettings(){
    var p=document.getElementById("mypageSettingsPanel");
    if(p&&p.parentNode!==document.body)document.body.appendChild(p);
    if(p)p.classList.add("open");
  }
  function closeMypageSettings(){
    var p=document.getElementById("mypageSettingsPanel");
    if(p)p.classList.remove("open");
  }
  window.openMypageSettings=openMypageSettings;
  window.closeMypageSettings=closeMypageSettings;
  document.addEventListener("click",function(e){
    var it=e.target.closest&&e.target.closest(".rail-item[data-view]");
    if(it&&it.dataset.view!=="mypage")closeMypageSettings();
  },true);
  var __koeAlbumLoaded=false;
  function switchProfileTab(tab){
    document.querySelectorAll(".profile-tab").forEach(function(b){b.classList.toggle("active",b.dataset.ptab===tab);});
    var map={posts:"profileTabPosts",album:"profileTabAlbum",bookmarks:"profileTabBookmarks"};
    Object.keys(map).forEach(function(k){var el=document.getElementById(map[k]);if(el)el.style.display=(k===tab)?"":"none";});
    if(tab==="album"&&!__koeAlbumLoaded){__koeAlbumLoaded=true;loadMyAlbum();}
    if(tab==="bookmarks"){try{loadBookmarks()}catch(e){}}
  }
  window.__koeReloadAlbum=function(){__koeAlbumLoaded=false;};
  async function loadMyAlbum(){
    var box=document.getElementById("profileTabAlbum");
    if(!box)return;
    box.innerHTML='<div class="empty-msg">読み込み中...</div>';
    var uid=(typeof myUserId!=="undefined"&&myUserId)||currentAccountId();
    var r=null;
    try{r=await callApi("get_user_posts",String(uid),"")}catch(e){r=null}
    if(!r||!r.ok){box.innerHTML='<div class="empty-msg">取得できませんでした</div>';return;}
    var withImg=(r.posts||[]).filter(function(p){return !!p.image_url;});
    if(!withImg.length){box.innerHTML='<div class="empty-msg">画像付きの投稿がありません</div>';return;}
    box.innerHTML=withImg.map(function(p){
      return '<img loading="lazy" decoding="async" src="'+escAttr(p.image_url)+'" onclick="openPostDetail(event,'+p.id+')" onerror="this.style.opacity=\'.15\'">';
    }).join("");
  }
  function openQuickEdit(){
    var m=document.getElementById("profileQuickEditModal");
    if(!m)return;
    var n=document.getElementById("quickEditName"),c=document.getElementById("quickEditComment");
    var srcN=document.getElementById("profileNameInput"),srcC=document.getElementById("profileCommentInput");
    if(n)n.value=srcN?srcN.value:"";
    if(c)c.value=srcC?srcC.value:"";
    var st=document.getElementById("quickEditStatus");if(st)st.textContent="";
    var bw=document.getElementById("quickEditBirthdayWrap"),bi=document.getElementById("quickEditBirthdayInput");
    if(bw)bw.style.display=window.__koeMyBirthday?"none":"block";
    if(bi)bi.value="";
    m.style.display="flex";
  }
  async function saveQuickEdit(){
    var n=document.getElementById("quickEditName"),c=document.getElementById("quickEditComment"),st=document.getElementById("quickEditStatus"),btn=document.getElementById("quickEditSaveBtn");
    var name=(n&&n.value||"").trim(),comment=(c&&c.value||"").trim();
    if(!name){if(st)st.textContent="名前を入力してください";return;}
    var birthday=window.__koeMyBirthday||"";
    if(!birthday){
      var bi2=document.getElementById("quickEditBirthdayInput");
      var bwv=(bi2&&bi2.value||"").trim();
      if(!/^[0-9]{8}$/.test(bwv)){
        if(st)st.textContent="生年月日は初回のみ必須です。YYYYMMDD形式(例: 19900101)で8桁入力してください";
        if(bi2)bi2.focus();
        return;
      }
      birthday=bwv;
    }
    if(btn)btn.disabled=true;
    if(st)st.textContent="保存中...";
    var r=await callApi("update_profile",name,comment,birthday);
    if(btn)btn.disabled=false;
    if(r&&r.ok){
      document.getElementById("profileQuickEditModal").style.display="none";
      try{toast("プロフィールを保存しました");sfx("success")}catch(e){}
      await loadProfile();
    }else{
      if(st)st.textContent="保存失敗: "+JSON.stringify(r&&(r.body||r.error)||"").slice(0,200);
    }
  }
  function bind(){
    var mb=document.getElementById("mypageMenuBtn");
    if(mb&&!mb.__koeBound){mb.__koeBound=true;mb.addEventListener("click",openMypageSettings);}
    var back=document.getElementById("mypageSettingsBack");
    if(back&&!back.__koeBound){back.__koeBound=true;back.addEventListener("click",closeMypageSettings);}
    var edit=document.getElementById("profileEditPillBtn");
    if(edit&&!edit.__koeBound){edit.__koeBound=true;edit.addEventListener("click",openQuickEdit);}
    var qr=document.getElementById("profileQrPillBtn");
    if(qr&&!qr.__koeBound){qr.__koeBound=true;qr.addEventListener("click",function(){if(typeof shareMyProfileLink==="function")shareMyProfileLink();});}
    var qeClose=document.getElementById("quickEditClose"),qeCancel=document.getElementById("quickEditCancel");
    [qeClose,qeCancel].forEach(function(b){if(b&&!b.__koeBound){b.__koeBound=true;b.addEventListener("click",function(){document.getElementById("profileQuickEditModal").style.display="none";});}});
    var qeSave=document.getElementById("quickEditSaveBtn");
    if(qeSave&&!qeSave.__koeBound){qeSave.__koeBound=true;qeSave.addEventListener("click",saveQuickEdit);}
    document.querySelectorAll(".profile-tab").forEach(function(b){
      if(b.__koeBound)return;b.__koeBound=true;
      b.addEventListener("click",function(){switchProfileTab(b.dataset.ptab);});
    });
  }
  if(document.readyState==="loading"){document.addEventListener("DOMContentLoaded",bind)}else{bind()}
  setTimeout(bind,1200);
})();

(function(){
  var NOTIF_CATEGORIES=[
    {key:"post",label:"投稿",sub:"いいね・コメントなど",color:"#F1436B",types:[1,17,18,0,13,2]},
    {key:"follow",label:"ユーザー",sub:"フォローなど",color:"#3FBF6B",types:[5,6]},
    {key:"chat",label:"チャット",sub:"メッセージ通知",color:"#2AC1C7",types:[4]},
    {key:"call",label:"通話",sub:"着信・トークリクエストなど",color:"#F0883E",types:[9,10,21,22,101,102]},
    {key:"circle",label:"サークル",sub:"コミュニティ関連",color:"#9B6DFF",types:[11,12,14,15,16,19,20]},
    {key:"gift",label:"ギフト",sub:"ギフト受け取り",color:"#E9B23C",types:[7]},
    {key:"other",label:"その他",sub:"上記以外の通知",color:"#6B7280",types:null}
  ];
  window.NOTIF_CATEGORIES=NOTIF_CATEGORIES;
  function notifCatKeyForType(type){
    var t=parseInt(type,10);
    for(var i=0;i<NOTIF_CATEGORIES.length;i++){
      var c=NOTIF_CATEGORIES[i];
      if(c.types&&c.types.indexOf(t)!==-1)return c.key;
    }
    return "other";
  }
  window.notifCatKeyForType=notifCatKeyForType;
  function getMutedNotifCats(){
    try{var a=JSON.parse(localStorage.getItem("koe_notif_muted_cats"));return Array.isArray(a)?a:[];}catch(e){return [];}
  }
  function setMutedNotifCats(arr){
    try{localStorage.setItem("koe_notif_muted_cats",JSON.stringify(arr));}catch(e){}
  }
  window.isNotifTypeMuted=function(type){
    var muted=getMutedNotifCats();
    return muted.indexOf(notifCatKeyForType(type))!==-1;
  };
  function renderNotifCatSettings(boxId){
    var box=document.getElementById(boxId||"notifCatList");
    if(!box)return;
    var muted=getMutedNotifCats();
    box.innerHTML=NOTIF_CATEGORIES.map(function(c){
      var on=muted.indexOf(c.key)===-1;
      return '<div class="notif-cat-row"><span class="notif-cat-label"><span class="notif-type-badge" style="background:'+c.color+';position:static;width:20px;height:20px;border-radius:50%;display:inline-flex;align-items:center;justify-content:center;"></span><span>'+c.label+'<br><small style="opacity:.6;font-weight:400;">'+c.sub+'</small></span></span><label class="koe-switch"><input type="checkbox" data-notifcat="'+c.key+'" '+(on?"checked":"")+'><span class="koe-switch-track"></span></label></div>';
    }).join("");
    box.querySelectorAll('input[data-notifcat]').forEach(function(inp){
      inp.addEventListener("change",function(){
        var key=inp.dataset.notifcat;
        var m=getMutedNotifCats();
        var idx=m.indexOf(key);
        if(inp.checked){if(idx!==-1)m.splice(idx,1);}else{if(idx===-1)m.push(key);}
        setMutedNotifCats(m);
        try{sfx("toggle")}catch(e){}
        try{document.querySelectorAll('input[data-notifcat="'+key+'"]').forEach(function(o){if(o!==inp)o.checked=inp.checked;});}catch(e){}
        try{if(typeof currentNotifKind!=="undefined")loadNotifications(currentNotifKind);}catch(e){}
        try{checkNotifications();}catch(e){}
      });
    });
  }
  var NOTIF_TABS=[{key:"normal",label:"通常"},{key:"important",label:"重要"},{key:"follow",label:"フォロー"},{key:"calls",label:"着信"}];
  function getHiddenNotifTabs(){
    try{var a=JSON.parse(localStorage.getItem("koe_notif_hidden_tabs"));return Array.isArray(a)?a:[];}catch(e){return [];}
  }
  function setHiddenNotifTabs(arr){
    try{localStorage.setItem("koe_notif_hidden_tabs",JSON.stringify(arr));}catch(e){}
  }
  function applyNotifTabVisibility(){
    var hidden=getHiddenNotifTabs();
    var chips=document.querySelectorAll(".notif-kind-chip");
    var visibleAny=false;
    chips.forEach(function(c){
      var h=hidden.indexOf(c.dataset.kind)!==-1;
      c.style.display=h?"none":"";
      if(!h)visibleAny=true;
    });
    if(visibleAny){
      var activeHidden=hidden.indexOf((typeof currentNotifKind!=="undefined"&&currentNotifKind)||"normal")!==-1;
      if(activeHidden){
        var firstVisible=Array.prototype.find.call(chips,function(c){return c.style.display!=="none";});
        if(firstVisible&&typeof loadNotifications==="function")loadNotifications(firstVisible.dataset.kind);
      }
    }
  }
  window.applyNotifTabVisibility=applyNotifTabVisibility;
  function renderNotifTabSettings(){
    var box=document.getElementById("notifTabListPage");
    if(!box)return;
    var hidden=getHiddenNotifTabs();
    box.innerHTML=NOTIF_TABS.map(function(t){
      var on=hidden.indexOf(t.key)===-1;
      return '<div class="notif-cat-row"><span class="notif-cat-label"><span>'+t.label+'</span></span><label class="koe-switch"><input type="checkbox" data-notiftab="'+t.key+'" '+(on?"checked":"")+'><span class="koe-switch-track"></span></label></div>';
    }).join("");
    box.querySelectorAll('input[data-notiftab]').forEach(function(inp){
      inp.addEventListener("change",function(){
        var key=inp.dataset.notiftab;
        var h=getHiddenNotifTabs();
        var idx=h.indexOf(key);
        if(inp.checked){if(idx!==-1)h.splice(idx,1);}else{if(idx===-1)h.push(key);}
        setHiddenNotifTabs(h);
        try{sfx("toggle")}catch(e){}
        applyNotifTabVisibility();
      });
    });
  }
  function bind(){
    var sec=document.getElementById("notifSettingsSection");
    if(sec&&!sec.__koeBound){sec.__koeBound=true;sec.addEventListener("toggle",function(){if(sec.open)renderNotifCatSettings("notifCatList");});}
    var gear=document.getElementById("notifSettingsGearBtn");
    if(gear&&!gear.__koeBound){gear.__koeBound=true;gear.addEventListener("click",function(){
      renderNotifCatSettings("notifCatListPage");
      renderNotifTabSettings();
      document.getElementById("notifSettingsPageModal").style.display="flex";
    });}
    var close=document.getElementById("notifSettingsPageClose");
    if(close&&!close.__koeBound){close.__koeBound=true;close.addEventListener("click",function(){document.getElementById("notifSettingsPageModal").style.display="none";});}
    applyNotifTabVisibility();
  }
  if(document.readyState==="loading"){document.addEventListener("DOMContentLoaded",bind)}else{bind()}
  setTimeout(bind,1200);
})();

(function(){
  function bind(){
    var mb=document.getElementById("profileViewMenuBtn");
    if(mb&&!mb.__koeBound){mb.__koeBound=true;mb.addEventListener("click",function(e){
      e.stopPropagation();
      var m=document.getElementById("profileViewActionsMenu");
      if(!m)return;
      m.style.display=m.style.display==="none"?"block":"none";
    });}
    var menu=document.getElementById("profileViewActionsMenu");
    if(menu&&!menu.__koeBound){menu.__koeBound=true;menu.addEventListener("click",function(e){
      if(e.target.closest("button")){setTimeout(function(){menu.style.display="none";},80);}
    });}
    if(!document.__koePvMenuOutside){document.__koePvMenuOutside=true;document.addEventListener("click",function(e){
      var m=document.getElementById("profileViewActionsMenu");
      if(!m||m.style.display==="none")return;
      var wrap=document.querySelector(".pv-menu-wrap");
      if(wrap&&wrap.contains(e.target))return;
      m.style.display="none";
    });}
    var pvm=document.getElementById("profileViewModal");
    if(pvm&&window.MutationObserver&&!pvm.__koeChatRestoreObs){
      pvm.__koeChatRestoreObs=true;
      var mo=new MutationObserver(function(){
        if(pvm.style.display==="none"&&window.__koeProfileOpenedFromChat){
          window.__koeProfileOpenedFromChat=false;
          var cm=document.getElementById("chatModal");
          if(cm)cm.style.display="flex";
        }
      });
      mo.observe(pvm,{attributes:true,attributeFilter:["style"]});
    }
  }
  if(document.readyState==="loading"){document.addEventListener("DOMContentLoaded",bind)}else{bind()}
  setTimeout(bind,1200);
})();

(function(){
  // koetomo://profile/{id} ディープリンク、および通話中通知タップ時の
  // 「通話に戻る」シグナルをMainActivity(Java)から受け取るためのグローバル橋渡し関数。
  window.__koeOpenProfileFromLink=function(id){
    try{
      if(!id)return;
      var tryOpen=function(n){
        if(typeof viewProfile==="function"&&document.getElementById("profileViewModal")){
          try{viewProfile(id);}catch(e){}
          return;
        }
        if(n>0)setTimeout(function(){tryOpen(n-1);},300);
      };
      tryOpen(15);
    }catch(e){}
  };
  window.__koeShowCallIfActive=function(){
    try{
      if((typeof skCurrentRoomId!=="undefined"&&skCurrentRoomId)||(typeof skRoom!=="undefined"&&skRoom)){
        if(typeof reopenCall==="function")reopenCall();
      }
    }catch(e){}
  };
})();


// ==== 外部リンクを既定ブラウザで開く(ネイティブ橋渡し優先・http(s)のみ) ====
window.koeOpenExternal=function(url){
  try{ if(window.AndroidApi&&window.AndroidApi.openUrl){ window.AndroidApi.openUrl(url); return; } }catch(e){}
  try{ window.open(url,"_blank"); }catch(e){}
};

// ==== 「もっと読む」/自動読み込みが空振りする問題の対策 ====
// 1ページ分が全部フィルタ(isFilteredPost)や重複除去(__tlKeep)で消えると、
// 空文字をappendするだけになり「押しても何も増えない」状態になっていた。
// 追加読み込み時は、実際にカードが増えるまで最大5ページ先まで自動で辿る。
(function(){
  if(window.__tlLoadMoreFix) return; window.__tlLoadMoreFix=true;
  var orig=window.loadTimeline;
  if(typeof orig!=="function") return;
  window.loadTimeline=function(append){
    if(!append) return orig.apply(this,arguments);
    var self=this;
    return (async function(){
      var list=document.getElementById("timelineList");
      var count=function(){ return list?list.querySelectorAll(".timeline-card").length:0; };
      var before=count();
      for(var i=0;i<5;i++){
        try{ await orig.call(self,true); }catch(e){ break; }
        if(count()>before) return;                                    // 実際に増えた
        if(!document.getElementById("timelineLoadMoreRow")) return;   // もう次のページが無い
      }
      try{ if(typeof toast==="function") toast("これ以上読み込める投稿がありません"); }catch(e){}
    })();
  };
})();

// ==== GitHub(最新版DL)ボタン ====
(function(){
  if(window.__koeGhWired) return; window.__koeGhWired=true;
  var URL_GH="https://github.com/haizarakun/koetomoProject";
  function bindGh(){
    var b=document.getElementById("koeGithubBtn");
    if(b&&b.__b!==1){ b.__b=1; b.addEventListener("click",function(){ window.koeOpenExternal(URL_GH); }); }
  }
  if(document.readyState==="loading") document.addEventListener("DOMContentLoaded",bindGh); else bindGh();
  document.addEventListener("click",function(){ setTimeout(bindGh,80); },true);
})();


// ==== アプリ内アップデート(GitHub Releases) ====
// 新しいリリースが出たら起動時に検知して知らせ、ワンタップでDL→インストール画面まで進める。
// ※Androidの仕様上インストールの最終確認は必ずユーザーが行う(無音インストールは不可)。
(function(){
  if(window.__koeUpdater) return; window.__koeUpdater=true;
  var API="https://api.github.com/repos/haizarakun/koetomoProject/releases/latest";
  var RELEASES="https://github.com/haizarakun/koetomoProject/releases/latest";
  var cur=null, manual=false;

  function A(){ try{ return window.AndroidApi; }catch(e){ return null; } }
  function T(m,t){ try{ if(typeof toast==="function") toast(m,t); }catch(e){} }
  function esc(x){ try{ return escapeHtml(String(x==null?"":x)); }catch(e){ return ""; } }
  function nums(v){ return String(v||"").replace(/^[vV]/,"").replace(/[-+].*$/,"").split(/[^0-9]+/).filter(function(x){return x!==""}).map(Number); }
  function isNewer(a,b){ var x=nums(a),y=nums(b),n=Math.max(x.length,y.length);
    for(var i=0;i<n;i++){ var p=x[i]||0,q=y[i]||0; if(p>q)return true; if(p<q)return false; } return false; }

  function current(){
    try{ var a=A(); if(a&&a.appVersion){ var r=JSON.parse(a.appVersion()); if(r&&r.ok) return r; } }catch(e){}
    return null;
  }
  function paintVersion(extra){
    var el=document.getElementById("koeVersionLine"); if(!el) return;
    if(!cur) cur=current();
    el.textContent="現在のバージョン: "+((cur&&cur.name)||"不明")+(extra?" ・ "+extra:"");
  }

  function startUpdate(r){
    var a=A();
    if(!r||!r.apk){ T("更新ファイルが見つかりません。GitHubから取得してください","error"); try{window.koeOpenExternal(RELEASES);}catch(e){} return; }
    try{ if(a&&a.canInstallApks&&!a.canInstallApks()){
      T("インストールの許可が必要です。設定画面を開きます","error");
      try{ a.openInstallSettings(); }catch(e){}
      return;
    } }catch(e){}
    try{ a.downloadUpdate(r.apk); }catch(e){ T("更新を開始できませんでした","error"); try{window.koeOpenExternal(RELEASES);}catch(x){} }
  }

  function showUpdateModal(r,curName){
    if(document.getElementById("koeUpdateModal")) return;
    var notes=String(r.notes||"").slice(0,600);
    var m=document.createElement("div"); m.className="modal"; m.id="koeUpdateModal"; m.style.display="flex";
    m.innerHTML='<div class="modal-content small"><div class="modal-header"><span>新しいバージョンがあります</span><button class="modal-close koe-up-x">✕</button></div><div class="modal-body">'
      +'<p class="page-desc" style="white-space:normal;">現在 <b>'+esc(curName)+'</b> → 最新 <b>'+esc(r.tag)+'</b></p>'
      +(notes?'<div class="card-sub" style="white-space:pre-wrap;overflow-y:auto;text-overflow:clip;max-height:170px;margin:6px 0;">'+esc(notes)+'</div>':'')
      +'<div style="display:flex;gap:8px;margin-top:12px;flex-wrap:wrap;"><button type="button" class="btn-primary koe-up-go" style="width:auto;">今すぐ更新</button><button type="button" class="btn-secondary koe-up-later" style="width:auto;">あとで</button></div>'
      +'</div></div>';
    document.body.appendChild(m);
    function close(){ try{m.remove()}catch(e){} }
    m.querySelector(".koe-up-x").addEventListener("click",close);
    m.querySelector(".koe-up-later").addEventListener("click",close);
    m.addEventListener("click",function(e){ if(e.target===m) close(); });
    m.querySelector(".koe-up-go").addEventListener("click",function(){ startUpdate(r); close(); });
  }

  window.__koeOnUpdateInfo=function(js){
    var r=null; try{ r=JSON.parse(js); }catch(e){}
    var wasManual=manual; manual=false;
    if(!r||!r.ok||!r.tag){ if(wasManual) T("更新の確認に失敗しました","error"); paintVersion();
      /* GitHub 側の 403(レート制限)/404(リリース未作成) 等はこちらの改変ではないので「確認済み」扱いにし、ロックしない。通信不能・5xx のみ猶予カウント */
      var st=r&&Number(r.status)||0; if(st>=400&&st<500){ markVerified(); } else { checkGrace(); } return; }
    if(!cur) cur=current();
    var curName=(cur&&cur.name)||"";
    if(!curName){ /* 自分のバージョンが読めない環境(ブラウザ等)では強制更新しない */ markVerified(); paintVersion(); return; }
    if(!isNewer(r.tag,curName)){ markVerified(); paintVersion("最新です"); if(wasManual) T("最新版を使っています ("+curName+")"); return; }
    paintVersion("新しい "+r.tag+" があります");
    /* 新しいバージョンが公開されている場合は必ず更新させる(閉じられない更新画面)。
       説明文に OPTIONAL_UPDATE と書いた場合だけ、従来どおり「あとで」を選べる案内にする。 */
    var optional=/OPTIONAL_UPDATE/i.test(String(r.notes||""));
    if(!optional){ showForcedUpdate(r,curName,r.tag); return; }
    markVerified();
    showUpdateModal(r,curName);
  };

  /* 更新確認ができない状態が続いた場合の保護(fail-closed):
     最後に確認できてから72時間を超えて、かつ今回も確認できないなら使用を止める。
     初回起動から72時間は猶予(オフラインでの初回セットアップ用)。 */
  var GRACE_MS=72*3600*1000;
  function markVerified(){ try{ localStorage.setItem("koe_upd_lastok",String(Date.now())); }catch(e){} }
  function checkGrace(){
    try{
      var now=Date.now();
      var last=parseInt(localStorage.getItem("koe_upd_lastok")||"0",10)||0;
      var first=parseInt(localStorage.getItem("koe_upd_first")||"0",10)||0;
      if(!first){ first=now; localStorage.setItem("koe_upd_first",String(now)); }
      var ref=last||first;
      if(now-ref>GRACE_MS) showBlockedNoCheck();
    }catch(e){}
  }
  function showBlockedNoCheck(){
    if(document.getElementById("koeForceUpdate")) return;
    var m=document.createElement("div"); m.id="koeForceUpdate";
    m.style.cssText="position:fixed;inset:0;z-index:99998;background:var(--bg-content,#14161c);display:flex;align-items:center;justify-content:center;padding:24px;";
    m.innerHTML='<div style="max-width:420px;width:100%;text-align:center;">'
      +'<div style="font-size:44px;margin-bottom:10px;">📡</div>'
      +'<div style="font-size:20px;font-weight:800;color:var(--text-header,#fff);margin-bottom:8px;">更新の確認が必要です</div>'
      +'<p class="page-desc" style="white-space:normal;">しばらく最新版かどうかを確認できていません。<br>インターネットに接続してから「再確認」を押してください。</p>'
      +'<button type="button" class="btn-primary koe-force-retry" style="margin-top:16px;">再確認</button>'
      +'<button type="button" class="btn-secondary koe-force-web" style="margin-top:8px;">GitHub で開く</button>'
      +'</div>';
    document.body.appendChild(m);
    m.querySelector(".koe-force-retry").addEventListener("click",function(){ try{m.remove()}catch(e){} window.__koeCheckUpdate(true); });
    m.querySelector(".koe-force-web").addEventListener("click",function(){ try{window.koeOpenExternal(RELEASES);}catch(e){} });
  }

  function showForcedUpdate(r,curName,minVer){
    try{ var old=document.getElementById("koeUpdateModal"); if(old) old.remove(); }catch(e){}
    if(document.getElementById("koeForceUpdate")) return;
    var m=document.createElement("div"); m.id="koeForceUpdate";
    m.style.cssText="position:fixed;inset:0;z-index:99998;background:var(--bg-content,#14161c);display:flex;align-items:center;justify-content:center;padding:24px;";
    m.innerHTML='<div style="max-width:420px;width:100%;text-align:center;">'
      +'<div style="font-size:44px;margin-bottom:10px;">🔒</div>'
      +'<div style="font-size:20px;font-weight:800;color:var(--text-header,#fff);margin-bottom:8px;">更新が必要です</div>'
      +'<p class="page-desc" style="white-space:normal;">このバージョン（<b>'+esc(curName)+'</b>）は使用できなくなりました。<br>続けるには <b>'+esc(r.tag)+'</b> に更新してください。</p>'
      +'<button type="button" class="btn-primary koe-force-go" style="margin-top:16px;">今すぐ更新</button>'
      +'<button type="button" class="btn-secondary koe-force-web" style="margin-top:8px;">GitHub で開く</button>'
      +'</div>';
    document.body.appendChild(m);
    m.querySelector(".koe-force-go").addEventListener("click",function(){ startUpdate(r); });
    m.querySelector(".koe-force-web").addEventListener("click",function(){ try{window.koeOpenExternal(RELEASES);}catch(e){} });
    /* 裏で動いている通話・ポーリングを止める */
    try{ if(typeof leaveInWindowCall==="function") leaveInWindowCall(); }catch(e){}
    try{ if(typeof stopApplicantPolling==="function") stopApplicantPolling(); }catch(e){}
  }

  window.__koeCheckUpdate=function(isManual){
    manual=!!isManual;
    cur=current(); paintVersion(isManual?"確認中…":"");
    try{ var a=A(); if(a&&a.checkUpdate){ a.checkUpdate(API); } else if(isManual){ T("この環境では更新確認を使えません","error"); } }
    catch(e){ if(isManual) T("更新の確認に失敗しました","error"); }
  };

  function bindBtns(){
    var b=document.getElementById("koeUpdateCheckBtn");
    if(b&&b.__b!==1){ b.__b=1; b.addEventListener("click",function(){ window.__koeCheckUpdate(true); }); }
    if(document.getElementById("koeVersionLine")) paintVersion();
  }
  function boot(){ bindBtns(); setTimeout(function(){ window.__koeCheckUpdate(false); },2500); /* 起動中も1時間ごとに再確認(MIN_VERSION が後から書かれた場合に効かせる) */ setInterval(function(){ try{ if(!document.hidden) window.__koeCheckUpdate(false); }catch(e){} },3600000); }
  if(document.readyState==="loading") document.addEventListener("DOMContentLoaded",boot); else boot();
  document.addEventListener("click",function(){ setTimeout(bindBtns,80); },true);
})();

/* ---- Xでログイン(OAuth2 + PKCE / パブリッククライアント) ---- */
(function(){
  if(window.__koeXLoginInit) return; window.__koeXLoginInit=true;
  function A(){ try{ return window.AndroidApi||null; }catch(e){ return null; } }
  function errEl(){ return document.getElementById("loginError"); }
  function showErr(msg){
    var e=errEl(); if(!e) return;
    e.style.display="block"; e.textContent=msg;
    try{ if(typeof __koeAutoDiagOnFail==="function") __koeAutoDiagOnFail(); }catch(_e){}
  }
  function setBusy(on){
    var b=document.getElementById("xLoginBtn"); if(!b) return;
    b.disabled=!!on;
    var sp=b.querySelector("span");
    if(sp) sp.textContent = on ? "Xで認証中..." : "Xでログイン";
  }
  // ネイティブから結果が返ってくる
  window.__koeOnXLogin=function(js){
    setBusy(false);
    var r=null;
    try{ r=(typeof js==="string")?JSON.parse(js):js; }catch(e){ r=null; }
    if(!r){ showErr("Xログインの応答を解釈できませんでした"); return; }
    if(r.ok){
      window.__koeLoginMethod="x";
      try{ if(typeof enterMain==="function") return enterMain(r.user_name); }catch(e){}
      showErr("ログインは成功しましたが画面遷移に失敗しました。アプリを再起動してください");
      return;
    }
    var m=r.message||"Xログインに失敗しました";
    if(r.status) m+=" (HTTP "+r.status+")";
    if(r.raw) m+=" / 応答: "+String(r.raw).slice(0,200);
    showErr(m);
  };
  function bind(){
    var b=document.getElementById("xLoginBtn");
    if(!b||b.__koeBound) return;
    b.__koeBound=true;
    var api=A();
    // アプリ以外(ブラウザ)や未設定ビルドではボタンを隠す
    if(!api||!api.startXLogin||(api.isXLoginConfigured&&!api.isXLoginConfigured())){
      b.style.display="none";
      var note=document.getElementById("xLoginNote"); if(note) note.style.display="none";
      return;
    }
    b.addEventListener("click",function(){
      var e=errEl(); if(e){ e.style.display="none"; e.textContent=""; }
      setBusy(true);
      try{ api.startXLogin(); }catch(err){ setBusy(false); showErr("Xの認証を開始できませんでした: "+err); }
      // 認証画面から戻ってこないまま放置された場合にボタンを戻す
      setTimeout(function(){ setBusy(false); },120000);
    });
  }
  if(document.readyState==="loading")document.addEventListener("DOMContentLoaded",bind);else bind();
  setTimeout(bind,1200);
})();

/* ---- LINE / Facebook のIDでログイン(取得済みIDを流用) ---- */
(function(){
  if(window.__koeSocialIdInit) return; window.__koeSocialIdInit=true;
  function errEl(){ return document.getElementById("loginError"); }
  function showErr(msg){ var e=errEl(); if(e){ e.style.display="block"; e.textContent=msg; } }
  async function doSocial(kind, inputId, btnId, apiName, label){
    var inp=document.getElementById(inputId), btn=document.getElementById(btnId);
    if(!inp||!btn) return;
    var id=(inp.value||"").trim();
    var e=errEl(); if(e){ e.style.display="none"; e.textContent=""; }
    if(!id){ showErr(label+" ID を入力してください"); return; }
    btn.disabled=true; var t=btn.textContent; btn.textContent="...";
    try{
      var r=await api()[apiName](id);
      if(r&&r.ok){ window.__koeLoginMethod=(kind==="line"?"line":"facebook"); if(typeof enterMain==="function") return enterMain(r.user_name); }
      showErr((r&&(r.message||r.error))||(label+"ログインに失敗しました")+(r&&r.raw?" / "+String(r.raw).slice(0,150):""));
    }catch(err){ showErr(label+"ログイン処理でエラー: "+(err&&err.message?err.message:String(err))); }
    finally{ btn.disabled=false; btn.textContent=t; }
  }
  function bind(){
    var lb=document.getElementById("lineLoginBtn");
    if(lb&&!lb.__b){ lb.__b=true; lb.addEventListener("click",function(){ doSocial("line","lineIdInput","lineLoginBtn","line_login","LINE"); }); }
    var fb=document.getElementById("fbLoginBtn");
    if(fb&&!fb.__b){ fb.__b=true; fb.addEventListener("click",function(){ doSocial("fb","fbIdInput","fbLoginBtn","facebook_login","Facebook"); }); }
  }
  if(document.readyState==="loading")document.addEventListener("DOMContentLoaded",bind);else bind();
  setTimeout(bind,1200);
})();

/* ---- タイムラインのフォロー中/オープンを左右スワイプで切替(タイムライン表示時のみ) ---- */
(function(){
  if(window.__koeFeedSwipeInit) return; window.__koeFeedSwipeInit=true;
  var FEEDS=["following","all"]; // 左=フォロー中 / 右=オープン
  function timelineActive(){
    var pg=document.getElementById("page-timeline");
    if(!pg||!pg.classList.contains("active")) return false;
    // モーダルが開いていたら無効
    var modals=document.querySelectorAll(".modal");
    for(var i=0;i<modals.length;i++){ if(getComputedStyle(modals[i]).display!=="none") return false; }
    return true;
  }
  function curFeedIndex(){
    try{ if(typeof timelineFeed!=="undefined") { var k=FEEDS.indexOf(timelineFeed); if(k>=0) return k; } }catch(e){}
    var a=document.querySelector('.main-feed-tab.timeline-feed-chip.active');
    return a?FEEDS.indexOf(a.dataset.feed):-1;
  }
  function go(dir){
    if(!timelineActive()) return;
    var i=curFeedIndex(); if(i<0) return;
    var ni=i+dir; if(ni<0||ni>=FEEDS.length) return;
    try{ if(typeof switchTimelineFeed==="function"){ switchTimelineFeed(FEEDS[ni]); try{haptic(8);}catch(e){} } }catch(e){}
  }
  function wire(){
    var el=document.getElementById("page-timeline");
    if(!el||el.__koeSwipe) return; el.__koeSwipe=true;
    var x0=0,y0=0,t0=0,tracking=false;
    el.addEventListener("touchstart",function(e){
      if(!timelineActive()||e.touches.length!==1){ tracking=false; return; }
      x0=e.touches[0].clientX; y0=e.touches[0].clientY; t0=Date.now(); tracking=true;
    },{passive:true});
    el.addEventListener("touchend",function(e){
      if(!tracking) return; tracking=false;
      if(!timelineActive()) return;
      var t=e.changedTouches[0]; if(!t) return;
      var dx=t.clientX-x0, dy=t.clientY-y0, dt=Date.now()-t0;
      if(dt>500) return;
      if(Math.abs(dx)<70) return;
      if(Math.abs(dx)<Math.abs(dy)*2.0) return; // 横移動が縦の2倍以上のときだけ
      if(dx<0) go(1); else go(-1);  // 左スワイプ=オープン / 右スワイプ=フォロー中
    },{passive:true});
  }
  if(document.readyState==="loading")document.addEventListener("DOMContentLoaded",wire);else wire();
  setTimeout(wire,1500);
})();
