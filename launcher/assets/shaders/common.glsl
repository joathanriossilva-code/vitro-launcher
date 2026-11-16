// Cabeçalho comum dos shaders de interface: precisão, dados vindos do vértice e a distância
// com sinal até um retângulo arredondado (negativa dentro, positiva fora).
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 v_p;   // posição na tela em px, y para baixo
varying vec2 v_uv;  // 0..1 dentro do retângulo desenhado
varying vec2 v_w;   // coordenada do papel de parede para esta posição
float sdRR(vec2 p,vec2 h,float r){vec2 q=abs(p)-(h-vec2(r));return length(max(q,0.0))+min(max(q.x,q.y),0.0)-r;}
vec2 gradRR(vec2 p,vec2 h,float r){
  vec2 q=abs(p)-(h-vec2(r));
  vec2 s=vec2(p.x<0.0?-1.0:1.0,p.y<0.0?-1.0:1.0);
  if(q.x>=0.0||q.y>=0.0)return s*normalize(max(q,0.0)+0.00001);
  float gx=step(q.y,q.x);return s*vec2(gx,1.0-gx);
}
