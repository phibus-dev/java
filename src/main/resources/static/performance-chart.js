(() => {
  const palette = ['#1769cf','#138a55','#d97706','#c8102e','#6d28d9','#0f766e'];
  const clamp = (v,min,max) => Math.max(min,Math.min(max,v));
  const niceStep = raw => {
    if (!Number.isFinite(raw) || raw <= 0) return 1;
    const power = Math.pow(10, Math.floor(Math.log10(raw)));
    const scaled = raw / power;
    const factor = scaled <= 1 ? 1 : scaled <= 2 ? 2 : scaled <= 5 ? 5 : 10;
    return factor * power;
  };
  const fmt = value => {
    const a = Math.abs(value);
    if (a >= 1000) return value.toFixed(0);
    if (a >= 100) return value.toFixed(0);
    if (a >= 10) return value.toFixed(1);
    return value.toFixed(2);
  };
  const timeLabel = seconds => {
    if (!Number.isFinite(seconds)) return '';
    const s = Math.max(0, Math.round(seconds));
    const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), r = s % 60;
    return h ? `${h}:${String(m).padStart(2,'0')}:${String(r).padStart(2,'0')}` : `${m}:${String(r).padStart(2,'0')}`;
  };

  class PerformanceChart {
    constructor(canvas, options = {}) {
      this.canvas = canvas;
      this.ctx = canvas.getContext('2d');
      this.options = options;
      this.series = [];
      this.points = [];
      this.hoverIndex = -1;
      this.handleResize = () => this.draw();
      this.resizeObserver = typeof ResizeObserver === 'function' ? new ResizeObserver(this.handleResize) : null;
      if (this.resizeObserver) this.resizeObserver.observe(canvas);
      else window.addEventListener('resize', this.handleResize);
      canvas.addEventListener('mousemove', e => this.onMove(e));
      canvas.addEventListener('mouseleave', () => { this.hoverIndex = -1; this.draw(); });
    }
    setData(points, series) { this.points = points || []; this.series = series || []; this.draw(); }
    destroy() {
      if (this.resizeObserver) this.resizeObserver.disconnect();
      else window.removeEventListener('resize', this.handleResize);
    }
    dimensions() {
      const rect = this.canvas.getBoundingClientRect();
      const width = Math.max(320, Math.round(rect.width || 900));
      const height = Math.max(260, Math.round(this.options.height || rect.height || 320));
      const dpr = window.devicePixelRatio || 1;
      if (this.canvas.width !== width*dpr || this.canvas.height !== height*dpr) {
        this.canvas.width = width*dpr; this.canvas.height = height*dpr;
      }
      this.ctx.setTransform(dpr,0,0,dpr,0,0);
      return {width,height};
    }
    bounds() {
      const values = this.series.flatMap(s => this.points.map(p => Number(s.value(p))).filter(Number.isFinite));
      let min = values.length ? Math.min(...values) : 0;
      let max = values.length ? Math.max(...values) : 1;
      if (this.options.zeroBaseline !== false) min = Math.min(0,min);
      if (max === min) max = min + 1;
      const span = max - min;
      const step = niceStep(span / 5);
      min = Math.floor(min / step) * step;
      max = Math.ceil(max / step) * step;
      if (max === min) max += step;
      return {min,max,step};
    }
    xValue(p, i) { return this.options.xValue ? Number(this.options.xValue(p,i)) : i; }
    xLabel(p, i) { return this.options.xLabel ? this.options.xLabel(p,i) : String(i + 1); }
    layout(width,height) { return {left:72,right:22,top:36,bottom:52,width:width-94,height:height-88}; }
    draw() {
      const {width,height} = this.dimensions(), ctx = this.ctx, box = this.layout(width,height), b = this.bounds();
      ctx.clearRect(0,0,width,height);
      ctx.font = '12px Arial, sans-serif'; ctx.textBaseline = 'middle';
      const ticks = Math.max(2,Math.round((b.max-b.min)/b.step));
      ctx.strokeStyle='#d8e0e8';ctx.fillStyle='#53687c';ctx.lineWidth=1;
      for(let i=0;i<=ticks;i++){
        const value=b.min+i*b.step, y=box.top+box.height-(i/ticks)*box.height;
        ctx.beginPath();ctx.moveTo(box.left,y);ctx.lineTo(box.left+box.width,y);ctx.stroke();
        ctx.textAlign='right';ctx.fillText(fmt(value),box.left-10,y);
      }
      const n=this.points.length; const xTicks=Math.min(8,Math.max(1,n-1));
      for(let i=0;i<=xTicks;i++){
        const idx=n<=1?0:Math.round(i*(n-1)/xTicks), x=n<=1?box.left:box.left+(idx/(n-1))*box.width;
        ctx.beginPath();ctx.moveTo(x,box.top);ctx.lineTo(x,box.top+box.height);ctx.stroke();
        if(n){ctx.textAlign='center';ctx.fillText(this.xLabel(this.points[idx],idx),x,box.top+box.height+20);}
      }
      ctx.fillStyle='#334155';ctx.textAlign='left';ctx.font='600 12px Arial, sans-serif';
      if(this.options.yUnit) ctx.fillText(this.options.yUnit,box.left,18);
      if(this.options.xTitle){ctx.textAlign='center';ctx.fillText(this.options.xTitle,box.left+box.width/2,height-12);}
      const y=v=>box.top+box.height-((v-b.min)/(b.max-b.min))*box.height;
      this.series.forEach((s,si)=>{
        const color=s.color||palette[si%palette.length];ctx.strokeStyle=color;ctx.lineWidth=2.5;ctx.beginPath();
        this.points.forEach((p,i)=>{const v=Number(s.value(p));if(!Number.isFinite(v))return;const x=n<=1?box.left:box.left+(i/(n-1))*box.width,yy=y(v);i?ctx.lineTo(x,yy):ctx.moveTo(x,yy);});ctx.stroke();
        ctx.fillStyle=color;this.points.forEach((p,i)=>{const v=Number(s.value(p));if(!Number.isFinite(v))return;const x=n<=1?box.left:box.left+(i/(n-1))*box.width,yy=y(v);ctx.beginPath();ctx.arc(x,yy,3,0,Math.PI*2);ctx.fill();});
      });
      this.drawLegend(width);
      if(this.hoverIndex>=0&&this.hoverIndex<n)this.drawTooltip(this.hoverIndex,box,b,width,height);
    }
    drawLegend(width){const ctx=this.ctx;let x=76,y=18;ctx.font='12px Arial, sans-serif';this.series.forEach((s,i)=>{const text=s.name||`Series ${i+1}`,tw=ctx.measureText(text).width;ctx.fillStyle=s.color||palette[i%palette.length];ctx.fillRect(x,y-5,14,3);ctx.fillStyle='#334155';ctx.textAlign='left';ctx.fillText(text,x+20,y);x+=tw+54;if(x>width-180){x=76;y+=16;}});}
    drawTooltip(index,box,b,width,height){const ctx=this.ctx,p=this.points[index],n=this.points.length,x=n<=1?box.left:box.left+(index/(n-1))*box.width;ctx.strokeStyle='#64748b';ctx.setLineDash([4,4]);ctx.beginPath();ctx.moveTo(x,box.top);ctx.lineTo(x,box.top+box.height);ctx.stroke();ctx.setLineDash([]);
      const rows=[this.xLabel(p,index),...this.series.map(s=>`${s.name}: ${fmt(Number(s.value(p)))} ${s.unit||this.options.yUnit||''}`)];ctx.font='12px Arial, sans-serif';const w=Math.max(...rows.map(r=>ctx.measureText(r).width))+24,h=rows.length*20+16;const tx=clamp(x+12,8,width-w-8),ty=clamp(box.top+10,8,height-h-8);ctx.fillStyle='rgba(15,35,55,.94)';ctx.fillRect(tx,ty,w,h);ctx.fillStyle='#fff';ctx.textAlign='left';rows.forEach((r,i)=>ctx.fillText(r,tx+12,ty+16+i*20));
    }
    onMove(event){const rect=this.canvas.getBoundingClientRect(),box=this.layout(rect.width,rect.height||this.options.height||320),n=this.points.length;if(!n)return;const x=event.clientX-rect.left;this.hoverIndex=n===1?0:clamp(Math.round(((x-box.left)/box.width)*(n-1)),0,n-1);this.draw();}
  }
  window.EvoPerformanceChart = PerformanceChart;
  window.EvoChartTimeLabel = timeLabel;
})();
