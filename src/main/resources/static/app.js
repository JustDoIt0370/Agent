'use strict';
const $ = id => document.getElementById(id);
const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
let overview, selected, current, approval, books = [], signature = '', working = false;
const names = {HEALTHY:'健康',SLOW_SQL:'SQL 延迟',POOL_EXHAUSTION:'连接池耗尽',DOWNSTREAM_TIMEOUT:'下游超时',UNKNOWN:'证据不足'};
async function api(path, body) { const r = await fetch('/api'+path, body === undefined ? {} : {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)}); const data = await r.json(); if(!r.ok) throw new Error(data.error || '请求失败'); return data; }
function view(name) { for(const id of ['console','books','eval']) $(id).hidden = id !== name; }
async function action(fn) { if(working) return; working=true; $('notice').textContent=''; try { await fn(); } catch(e) { $('notice').textContent=e.message; } finally { working=false; await refresh().catch(()=>{}); } }
function mode() { return $('mode').value; }
async function diagnose() { const r=await api('/investigations',{mode:mode(),question:$('question').value}); selected=r.id; signature=''; view('console'); }
document.querySelectorAll('[data-view]').forEach(b=>b.addEventListener('click',()=>view(b.dataset.view)));
document.querySelectorAll('[data-scenario]').forEach(b=>b.addEventListener('click',()=>action(async()=>{await api('/lab/scenario',{scenario:b.dataset.scenario}); await diagnose();})));
$('healthy').onclick=()=>action(()=>api('/lab/scenario',{scenario:'HEALTHY'}));
$('diagnose').onclick=()=>action(diagnose);
$('history').onchange=()=>{selected=$('history').value;signature='';refresh();};
$('cancel').onclick=()=>$('approval').close();
$('confirm').onclick=()=>action(async()=>{ $('approval').close(); await api('/investigations/'+selected+'/approve',{windowId:current.windowId}); signature=''; });
$('evaluate').onclick=()=>action(async()=>{await api('/evaluations',{mode:mode()});view('eval');});
function review() { const book=books.find(b=>b.id===current.diagnosis.runbookId); $('review').innerHTML='<b>'+esc(book.actionLabel)+'</b><p>'+esc(book.risk)+'</p><p class="muted">回滚：'+esc(book.rollback)+'</p>'; $('approval').showModal(); }
function renderRun(run, receipt) {
 const key=JSON.stringify([run,receipt,overview.service.windowId]); if(key===signature)return; signature=key;
 const d=run.diagnosis; const isCurrent=run.windowId===overview.service.windowId;
 $('investigation').innerHTML='<div class="panel"><h3>调查轨迹 <span class="badge">'+esc(run.status)+'</span></h3><p class="muted">'+(run.mode==='MODEL'?'模型 Agent':'规则基线 · 无模型调用')+' · '+(run.durationMs/1000).toFixed(1)+'s · '+(run.inputTokens+run.outputTokens)+' tokens</p>'+run.steps.map(s=>'<div class="step"><b>'+esc(s.title)+'</b><small>'+esc(s.tool)+' '+esc(s.evidenceId)+' · '+s.durationMs+'ms</small></div>').join('')+(run.error?'<p>'+esc(run.error)+'</p>':'')+(d?'<div class="diagnosis"><span class="badge">证据支持度自评 '+Math.round(d.confidence*100)+'%</span><h4>'+esc(names[d.causeCode])+'</h4><p>'+esc(d.summary)+'</p><small>依据 '+d.evidenceIds.map(esc).join(' · ')+' / '+esc(d.runbookId)+'</small>'+(d.actionKey!=='NONE'&&!receipt?'<br><button id="repair" class="primary" '+(!isCurrent?'disabled':'')+'>'+(isCurrent?'审核修复建议':'历史窗口已失效')+'</button>':'')+(receipt?'<div class="receipt"><b>'+esc(receipt.status)+'</b><p>'+esc(receipt.message)+'</p>'+(receipt.verification?'<small>新样本 '+receipt.verification.samples+' · 错误 '+receipt.verification.errors+' · P95 '+receipt.verification.p95Ms+'ms</small>':'')+'</div>':'')+'</div>':'')+'</div><div class="panel"><h3>证据档案 <span class="badge">'+run.evidence.length+' 项</span></h3><p class="muted">查询结果绑定当前调查窗口，支持展开核对原始观测。</p>'+run.evidence.map(e=>'<details><summary>'+esc(e.id)+' / '+esc(e.tool)+'</summary><pre>'+esc(JSON.stringify(e.data,null,2))+'</pre></details>').join('')+'</div>';
 if($('repair'))$('repair').onclick=review;
}
async function refresh() {
 overview=await api('/overview'); const m=overview.service.metrics;
 $('connection').textContent=overview.busy || '● 本地服务在线';
 $('modelHint').textContent=overview.modelAvailable?'模型已配置：'+overview.model+'。模型模式通过工具调用动态调查。':'模型尚未配置。当前可使用规则基线完成故障闭环；设置 AGENT_API_KEY 后重启可使用模型 Agent。';
 $('mode').options[1].disabled=!overview.modelAvailable;
 $('metrics').innerHTML=[['请求 P95',m.p95Ms,'ms'],['请求错误率',(m.errorRate*100).toFixed(1),'%'],['活跃连接',m.poolActive,'/ '+m.poolCapacity],['窗口样本',m.samples,'requests']].map(v=>'<div class="metric"><small>'+v[0]+'</small><strong>'+v[1]+'<span>'+v[2]+'</span></strong></div>').join('');
 document.querySelectorAll('[data-scenario],#healthy,#diagnose,#evaluate').forEach(b=>b.disabled=!!overview.busy||working);
 const runs=await api('/investigations'); if(!selected&&runs.length)selected=runs[0].id;
 const historyHTML=runs.length?runs.map(r=>'<option value="'+esc(r.id)+'">'+esc(new Date(r.createdAt).toLocaleTimeString())+' · '+esc(r.diagnosis?names[r.diagnosis.causeCode]:r.status)+'</option>').join(''):'<option>尚无记录</option>';
 if($('history').innerHTML!==historyHTML)$('history').innerHTML=historyHTML; if(selected)$('history').value=selected;
 if(selected){current=await api('/investigations/'+selected);approval=(await api('/investigations/'+selected+'/approval')).approval;renderRun(current,approval);}
 const reports=await api('/evaluations'); $('reports').innerHTML=reports.map(r=>'<div class="panel book"><h3>'+esc(r.mode)+' · '+esc(r.status)+' · '+r.passed+'/'+r.total+'</h3><table><thead><tr><th>场景</th><th>诊断</th><th>结果</th></tr></thead><tbody>'+r.cases.map(c=>'<tr><td>'+esc(names[c.scenario])+'</td><td>'+esc(names[c.actual]||c.actual)+'</td><td>'+(c.passed?'通过':'未通过')+'</td></tr>').join('')+'</tbody></table>'+esc(r.error||'')+'</div>').join('');
}
async function init(){books=await api('/runbooks');$('books').innerHTML=books.map(b=>'<article class="panel book"><span class="badge">'+esc(b.id)+' / v'+esc(b.version)+'</span><h3>'+esc(b.title)+'</h3><p>'+esc(b.symptom)+'</p><ul>'+b.checks.map(c=>'<li>'+esc(c)+'</li>').join('')+'</ul><b>'+esc(b.actionLabel)+'</b><p>'+esc(b.risk)+'</p><p class="muted">回滚：'+esc(b.rollback)+'</p></article>').join('');await refresh();}
init().catch(e=>$('notice').textContent=e.message);setInterval(()=>refresh().catch(()=>$('connection').textContent='连接中断，请检查服务'),1500);
