// Miolo do vidro: a parte longe da borda, onde não há refração, dispersão nem brilho de contorno.
// É a mesma conta do shader completo com os termos que valem zero retirados e as constantes já
// combinadas fora daqui. Sai opaco, então a GPU nem calcula o papel de parede que fica por baixo.
uniform sampler2D u_wall,u_blur;
uniform vec4 u_c;   // peso do papel de parede, peso do desfoque, tinta, multiplicador de luz
uniform vec2 u_y;   // topo da peça (px) e 1/(1,2 * meia altura): o degradê claro da metade de cima
void main(){
  float t=clamp((v_p.y-u_y.x)*u_y.y,0.0,1.0);
  vec3 col=texture2D(u_wall,v_w).rgb*u_c.x+texture2D(u_blur,v_w).rgb*u_c.y+vec3(u_c.z+0.05*(1.0-t*t*(3.0-2.0*t)));
  gl_FragColor=vec4(col*u_c.w,1.0);
}
