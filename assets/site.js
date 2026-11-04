// 淡入 + 子元素错峰
  const io = new IntersectionObserver((entries) => {
    for (const e of entries) if (e.isIntersecting) { e.target.classList.add('in'); io.unobserve(e.target); }
  }, { rootMargin: '0px 0px -10% 0px', threshold: 0.08 });
  document.querySelectorAll('.reveal').forEach((el, i) => {
    el.style.transitionDelay = (i % 3) * 60 + 'ms';
    io.observe(el);
  });

  // 首屏背景视差 + 顶栏渐显 + 标语上推渐隐
  const heroBg = document.querySelector('.hero-bg');
  const heroEl = document.querySelector('.hero');
  const slogan = document.querySelector('.hero .slogan');
  const head = document.querySelector('header');
  const still = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  let queued = false;

  function onScroll() {
    const y = window.scrollY || window.pageYOffset;
    if (head) head.classList.toggle('solid', y > 40);
    if (heroBg && !still && y < heroEl.offsetHeight * 1.5) {
      heroBg.style.transform = 'translate3d(0,' + (y * 0.28).toFixed(2) + 'px,0)';
    }
    if (slogan) {
      const p = Math.min(Math.max(y / (heroEl.offsetHeight * 0.26), 0), 1);
      slogan.style.opacity = Math.pow(1 - p, 1.25).toFixed(3);
      slogan.style.transform = 'translate3d(0,' + (-y * 0.16).toFixed(2) + 'px,0)';
    }
    queued = false;
  }
  // 液态玻璃光斑：光点跟随指针，慢层插值产生拖影
  const glowEls = Array.from(document.querySelectorAll('.glow, .langs span, .chips .chip'));
  if (glowEls.length) {
    const st = new WeakMap();
    glowEls.forEach(el => st.set(el, {x:50, y:50, lx:50, ly:50}));
    let raf = null;
    function tick(){
      let busy = false;
      glowEls.forEach(el => {
        const v = st.get(el);
        if (Math.abs(v.lx-v.x) > .05 || Math.abs(v.ly-v.y) > .05) {
          v.lx += (v.x-v.lx) * 0.14; v.ly += (v.y-v.ly) * 0.14;
          el.style.setProperty('--lx', v.lx.toFixed(1)+'%');
          el.style.setProperty('--ly', v.ly.toFixed(1)+'%');
          busy = true;
        }
      });
      raf = busy ? requestAnimationFrame(tick) : null;
    }
    document.addEventListener('pointermove', (e) => {
      const el = e.target.closest ? e.target.closest('.glow, .langs span') : null;
      if (!el) return;
      const v = st.get(el); if (!v) return;
      const r = el.getBoundingClientRect();
      v.x = ((e.clientX-r.left)/r.width)*100;
      v.y = ((e.clientY-r.top)/r.height)*100;
      el.style.setProperty('--mx', v.x.toFixed(1)+'%');
      el.style.setProperty('--my', v.y.toFixed(1)+'%');
      if (!raf) raf = requestAnimationFrame(tick);
    }, {passive:true});
  }

  window.addEventListener('scroll', () => {
    if (!queued) { queued = true; requestAnimationFrame(onScroll); }
  }, { passive: true });
  onScroll();

// the drawn TV keeps real time, and reads the date in the page's own language
(function(){
  const timeEl = document.querySelector('[data-live-clock]');
  const dateEl = document.querySelector('[data-live-date]');
  if(!timeEl && !dateEl) return;
  const wd = {en:['Sunday','Monday','Tuesday','Wednesday','Thursday','Friday','Saturday'],
              zh:['星期日','星期一','星期二','星期三','星期四','星期五','星期六']};
  const mo = ['January','February','March','April','May','June','July','August','September','October','November','December'];
  const pad = n => String(n).padStart(2,'0');
  // the Chinese calendar's day, written the way a lock screen writes it: 初一 … 三十
  const lunarDay = n => n <= 10 ? '初' + '一二三四五六七八九十'[n-1]
    : n < 20 ? '十' + '一二三四五六七八九'[n-11]
    : n === 20 ? '二十' : n === 30 ? '三十' : '廿' + '一二三四五六七八九'[n-21];
  function lunarOf(d){
    try{
      const p = new Intl.DateTimeFormat('zh-CN-u-ca-chinese',{month:'long',day:'numeric'}).formatToParts(d);
      const month = (p.find(x=>x.type==='month')||{}).value || '';
      const day = parseInt((p.find(x=>x.type==='day')||{}).value,10);
      return month && day ? month + lunarDay(day) : '';
    }catch(e){ return ''; }
  }
  function tick(){
    const d = new Date();
    if(timeEl) timeEl.textContent = d.getHours() + ':' + pad(d.getMinutes());
    if(dateEl){
      const lang = (document.documentElement.lang||'en').toLowerCase();
      const zh = lang.startsWith('zh') || lang.startsWith('ja');
      const head = zh
        ? (d.getMonth()+1) + '月' + d.getDate() + '日 ' + wd.zh[d.getDay()]
        : wd.en[d.getDay()] + ', ' + mo[d.getMonth()] + ' ' + d.getDate();
      if(zh){
        const l = lunarOf(d);
        dateEl.innerHTML = '';
        dateEl.append(head);
        if(l){ const s = document.createElement('span'); s.className='tm-lunar'; s.textContent = '  ' + l; dateEl.append(s); }
      } else {
        dateEl.textContent = head;
      }
    }
  }
  tick(); setInterval(tick, 20000);
})();

// 长按语言/机型胶囊时，不弹系统的复制/翻译/链接菜单
document.addEventListener('contextmenu', (e) => {
  if (e.target.closest && e.target.closest('.langs span, .chips .chip')) e.preventDefault();
});

// 移动端：手指划过多语言气泡群，Apple Watch 式中间放大、两侧让位
(function(){
  const langs = document.querySelector('.langs');
  if (!langs || !('ontouchstart' in window)) return;
  const spans = Array.from(langs.querySelectorAll('span'));
  const rects = () => spans.map(sp => sp.getBoundingClientRect());
  let hot = -1;
  function setHot(i){
    if (i === hot) return;
    hot = i;
    const rs = rects();
    const sameRow = k => i > -1 && k > -1 && Math.abs((rs[k].top + rs[k].height/2) - (rs[i].top + rs[i].height/2)) < rs[i].height * 0.8;
    spans.forEach((sp, j) => {
      sp.classList.toggle('t-hot', j === i);
      sp.classList.toggle('t-near-l', sameRow(j) && j === i - 1);
      sp.classList.toggle('t-near-r', sameRow(j) && j === i + 1);
    });
  }
  function pick(x, y){
    let best = -1, bd = 1e9;
    spans.forEach((sp, j) => {
      const r = sp.getBoundingClientRect();
      if (!r.width) return;
      const dd = Math.hypot(x - (r.left + r.width/2), y - (r.top + r.height/2));
      const reach = Math.max(r.width, r.height) * 0.75;
      if (dd < reach && dd < bd) { bd = dd; best = j; }
    });
    return best;
  }
  const on = e => setHot(pick(e.touches[0].clientX, e.touches[0].clientY));
  langs.addEventListener('touchstart', on, {passive:true});
  langs.addEventListener('touchmove', on, {passive:true});
  langs.addEventListener('touchend', () => setHot(-1), {passive:true});
  langs.addEventListener('touchcancel', () => setHot(-1), {passive:true});
})();
