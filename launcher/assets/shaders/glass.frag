// O vidro, desenhado só no retângulo da peça: refração na borda, desfoque pré-calculado e brilho especular.
// Mesma matemática da versão de tela inteira; o miolo (longe da borda) segue por um caminho curto,
// porque ali a refração é zero e as três leituras de cor seriam idênticas.
uniform sampler2D u_wall,u_blur;
uniform vec2 u_inv;    // (1/largura, -1/altura): converte deslocamento em px para coordenada de textura
uniform float u_px;    // px por unidade de desenho
uniform vec4 u_shape;  // centro x, y, meia largura, meia altura (px)
uniform vec4 u_g1;     // raio, largura da borda, refração, desfoque
uniform vec4 u_g2;     // tinta, brilho especular, escurecimento, dispersão
uniform vec2 u_g3;     // opacidade, multiplicador de luz
vec3 bg(vec2 uv,float frost){return mix(texture2D(u_wall,uv).rgb,texture2D(u_blur,uv).rgb,frost);}
void main(){
  vec2 c=v_p-u_shape.xy;vec2 h=u_shape.zw;float r=u_g1.x;
  float sd=sdRR(c,h,r);
  float cov=clamp(0.5-sd,0.0,1.0);
  if(cov<=0.0){gl_FragColor=vec4(0.0);return;}
  float frost=u_g1.w;
  vec3 col;
  if(sd<=-u_g1.y){
    col=bg(v_w,frost);
  }else{
    float t=clamp(1.0+sd/u_g1.y,0.0,1.0);
    float d=(1.0-sqrt(1.0-t*t))*u_g1.z;
    vec2 off=-d*gradRR(c,h,min(r*1.5,min(h.x,h.y)))*u_inv;
    col.r=bg(v_w+off*(1.0+u_g2.w),frost).r;
    col.g=bg(v_w+off,frost).g;
    col.b=bg(v_w+off*(1.0-u_g2.w),frost).b;
  }
  col*=1.0-u_g2.z;
  col=mix(col,vec3(1.0),u_g2.x);
  col+=0.05*(1.0-smoothstep(-h.y,h.y*0.2,c.y));
  if(-sd<1.7*u_px){
    float rim=1.0-smoothstep(0.2,1.7*u_px,-sd);
    float nd=dot(gradRR(c,h,r),vec2(-0.55238,-0.83359));
    col+=rim*(0.10+pow(abs(nd),2.4)*u_g2.y);
  }
  float a=cov*u_g3.x;
  gl_FragColor=vec4(col*(u_g3.y*a),a);
}
