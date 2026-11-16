// Luz sob o app em foco, somada por cima do vidro da barra; também acende a borda inferior do vidro.
uniform float u_px;
uniform vec4 u_shape;  // a barra: centro x, y, meia largura, meia altura (px)
uniform float u_r;
uniform vec3 u_glow;   // x, y, largura (px)
uniform vec3 u_col;    // cor já multiplicada pela intensidade
void main(){
  float sd=sdRR(v_p-u_shape.xy,u_shape.zw,u_r);
  float cov=clamp(0.5-sd,0.0,1.0);
  vec2 d=v_p-u_glow.xy;
  float sx=u_glow.z*u_glow.z;
  float halo=exp(-d.x*d.x/sx-d.y*d.y/(196.0*u_px*u_px));
  float core=exp(-d.x*d.x/(sx*0.45)-d.y*d.y/(10.0*u_px*u_px));
  float edge=(1.0-smoothstep(0.2,2.0*u_px,-sd))*step(u_shape.y,v_p.y)*exp(-d.x*d.x/(sx*1.8));
  float lum=max(u_col.r,max(u_col.g,u_col.b));
  gl_FragColor=vec4((u_col*(0.46*halo+0.50*core)+vec3(lum)*0.5*edge)*cov,0.0);
}
