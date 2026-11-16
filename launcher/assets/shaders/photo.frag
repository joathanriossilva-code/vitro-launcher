// Copia uma foto para a textura do papel de parede, preenchendo a tela sem distorcer.
precision mediump float;
uniform sampler2D u_tex; uniform vec2 u_size; uniform vec2 u_fit;
void main(){
  vec2 uv=(gl_FragCoord.xy/u_size-0.5)*u_fit+0.5;
  gl_FragColor=vec4(texture2D(u_tex,vec2(uv.x,1.0-uv.y)).rgb,1.0);
}
