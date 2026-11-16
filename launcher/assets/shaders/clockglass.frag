// O relógio de vidro, desenhado uma vez por minuto numa textura própria (ver GlassClock e Gfx.bakeClock).
// A forma dos números chega numa textura: R = distância até a borda em faixa fina (borda nítida),
// G = distância em faixa larga (relevo e sombra), B e A = direção da borda, suavizada (zera no meio do
// traço, onde as duas bordas se encontram). Mesma conta do protótipo (prototipo/relogio.html).
uniform sampler2D u_wall,u_blur,u_sdf;
uniform vec2 u_inv;     // converte deslocamento em px para coordenada do papel de parede
uniform vec2 u_texel;   // 1 / tamanho da textura da forma
uniform vec2 u_range;   // faixa fina e faixa larga da distância (px)
uniform vec4 u_g1;      // relevo (px), refração (px), desfoque, branco
uniform vec4 u_g2;      // brilho, dispersão, contorno, px por unidade
uniform vec3 u_sh;      // sombra: força, espalhamento (px), deslocamento para baixo (px)
uniform vec2 u_band;    // alto e base dos algarismos na textura (0..1)
const vec2 LIGHT=vec2(-0.55238,-0.83359);
vec3 bg(vec2 uv,float f){return mix(texture2D(u_wall,uv).rgb,texture2D(u_blur,uv).rgb,f);}
void main(){
  vec4 s=texture2D(u_sdf,v_uv);
  float df=(s.r-0.5)*2.0*u_range.x;
  float d=abs(df)<u_range.x-0.3?df:(s.g-0.5)*2.0*u_range.y;
  float cov=clamp(0.5-d,0.0,1.0);
  // Sombra suave deslocada para baixo e um contorno fino, fora dos números.
  float dsh=(texture2D(u_sdf,v_uv-vec2(0.0,u_sh.z)*u_texel).g-0.5)*2.0*u_range.y;
  float x=max(dsh,0.0)/u_sh.y;
  float shade=u_sh.x*exp(-0.5*x*x);
  float line=u_g2.z*0.5*(1.0-smoothstep(0.0,1.5*u_g2.w,d));
  float outA=max(shade,line);
  if(cov<=0.0){gl_FragColor=vec4(0.0,0.0,0.0,outA);return;}
  // Refração: perto da borda o vidro mostra o fundo puxado para dentro, como uma lente de borda redonda.
  vec2 g=s.ba*2.0-1.0;
  float gm=length(g);
  vec2 n=g/max(gm,0.001);
  float inner=max(-d,0.0);
  float t=clamp(1.0-inner/u_g1.x,0.0,1.0);
  float prof=1.0-sqrt(1.0-t*t);
  vec2 off=-prof*u_g1.y*g*u_inv;
  vec3 col;
  col.r=bg(v_w+off*(1.0+u_g2.y),u_g1.z).r;
  col.g=bg(v_w+off,u_g1.z).g;
  col.b=bg(v_w+off*(1.0-u_g2.y),u_g1.z).b;
  // O branco do vidro se adapta ao fundo: mais forte sobre fundo escuro, mais fraco sobre fundo claro.
  float lum=dot(texture2D(u_blur,v_w).rgb,vec3(0.2126,0.7152,0.0722));
  col=mix(col,vec3(1.0),u_g1.w*mix(1.35,0.65,smoothstep(0.05,0.7,lum)));
  float nd=dot(n,LIGHT)*min(gm*1.6,1.0);
  float up=max(nd,0.0),dn=max(-nd,0.0),band=pow(prof,1.5);
  // Luz larga no relevo, dos dois lados como no vidro da Apple, e uma sombra leve no lado de baixo.
  col+=u_g2.x*band*(0.42*up*up+0.16*dn*dn);
  col*=1.0-u_g2.z*0.25*prof*dn;
  // Aro fino e brilhante na borda, mais forte de frente para a luz.
  float lit=pow(abs(nd),2.4)*(nd>0.0?1.0:0.6);
  float rim=1.0-smoothstep(0.2,1.7*u_g2.w,inner);
  col+=rim*(0.12+lit*u_g2.x);
  float vy=clamp((v_uv.y-u_band.x)/max(u_band.y-u_band.x,0.001),0.0,1.0);
  col+=0.05*(1.0-vy);
  gl_FragColor=vec4(col*cov,cov+(1.0-cov)*outA);
}
