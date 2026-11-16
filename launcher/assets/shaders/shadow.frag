// Escurece o que está embaixo: a tela é multiplicada por (1 - força * queda exponencial).
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform vec3 u_k;      // força, 1/queda (px), raio (px)
varying vec2 v_e;
void main(){
  float d=max(length(v_e)-u_k.z,0.0);
  gl_FragColor=vec4(vec3(1.0-u_k.x*exp(-d*u_k.y)),1.0);
}
