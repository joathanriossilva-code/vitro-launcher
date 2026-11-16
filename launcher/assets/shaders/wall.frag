// Os quatro papéis de parede gerados por código. Desenhados uma única vez numa textura.
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform vec2 u_res; uniform int u_kind;
float hash(vec2 p){return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}
float noise(vec2 p){vec2 i=floor(p),f=fract(p);f=f*f*(3.0-2.0*f);
  return mix(mix(hash(i),hash(i+vec2(1.0,0.0)),f.x),mix(hash(i+vec2(0.0,1.0)),hash(i+vec2(1.0,1.0)),f.x),f.y);}
float fbm(vec2 p){float a=0.5,s=0.0;for(int i=0;i<5;i++){s+=a*noise(p);p*=2.02;a*=0.5;}return s;}

vec3 aurora(vec2 uv){
  vec2 w=uv+0.30*vec2(fbm(uv*1.1+vec2(3.1,0.0)),fbm(uv*1.1+vec2(0.0,7.7)))-0.15;
  vec3 c=vec3(0.04,0.05,0.22);
  c=mix(c,vec3(0.10,0.32,0.98),smoothstep(1.0,0.1,length(w-vec2(0.40,0.95))));
  c=mix(c,vec3(0.62,0.20,0.90),smoothstep(0.8,0.05,length(w-vec2(1.15,0.55)))*0.85);
  c=mix(c,vec3(0.98,0.30,0.50),smoothstep(0.7,0.05,length(w-vec2(1.55,0.20)))*0.9);
  c=mix(c,vec3(1.0,0.66,0.28),smoothstep(0.45,0.0,length(w-vec2(1.85,-0.05))));
  c=mix(c,vec3(0.25,0.85,0.95),smoothstep(0.5,0.0,length(w-vec2(0.05,0.10)))*0.55);
  return c;
}
vec3 dunas(vec2 uv){
  vec3 c=mix(vec3(0.99,0.78,0.56),vec3(0.42,0.27,0.55),smoothstep(0.35,1.0,uv.y));
  c=mix(c,vec3(1.0,0.90,0.72),exp(-length(uv-vec2(1.30,0.66))*5.0)*0.8);
  for(int i=0;i<5;i++){
    float fi=float(i);
    float h=0.60-fi*0.125+0.085*sin(uv.x*(1.25+fi*0.42)+fi*1.9)+0.035*sin(uv.x*(2.9+fi*0.9)+fi*4.2);
    float m=smoothstep(h+0.003,h-0.003,uv.y);
    vec3 top=mix(vec3(0.97,0.62,0.40),vec3(0.24,0.10,0.26),fi/4.0);
    float shade=smoothstep(h,h-0.30,uv.y);
    c=mix(c,mix(top*1.14,top*0.60,shade),m);
  }
  return c;
}
vec3 seda(vec2 uv){
  vec3 c=vec3(0.012,0.016,0.045);
  for(int i=0;i<4;i++){
    float fi=float(i);
    float y=0.44+0.20*sin(uv.x*1.55+fi*1.25+0.6*sin(uv.x*0.9+fi*2.0))+(fi-1.5)*0.085;
    float d=uv.y-y;
    float fold=d<0.0?exp(d*4.5):exp(-d*42.0);
    vec3 hue=mix(vec3(0.08,0.34,1.0),vec3(0.78,0.24,0.95),fi/3.0);
    c+=hue*fold*0.36+vec3(0.82,0.9,1.0)*exp(-d*d*1400.0)*0.22;
  }
  return c;
}
vec3 orbita(vec2 uv){
  vec2 c0=vec2(0.889,-1.52); float R=1.95;
  float d=length(uv-c0)-R;
  vec2 sun=c0+R*normalize(vec2(0.42,1.0));
  float ds=length(uv-sun);
  vec3 col=vec3(0.006,0.008,0.022);
  col+=vec3(step(0.9972,hash(floor(uv*260.0))))*0.6*smoothstep(0.02,0.3,d);
  if(d>0.0){
    col+=vec3(0.16,0.44,1.0)*exp(-d*15.0)*0.95;
    col+=vec3(0.75,0.9,1.0)*exp(-d*120.0)*0.9;
  }else{
    float lit=exp(d*5.0);
    float cl=fbm(uv*vec2(3.0,8.0)+vec2(1.7,0.3));
    col=mix(vec3(0.004,0.010,0.035),vec3(0.05,0.22,0.56),lit);
    col+=vec3(0.6,0.76,0.92)*smoothstep(0.48,0.8,cl)*lit*0.38;
    col+=vec3(0.75,0.9,1.0)*exp(d*90.0)*0.6;
  }
  col+=vec3(1.0,0.80,0.58)*exp(-ds*7.0)*0.55+vec3(1.0)*exp(-ds*40.0)*0.9;
  return col;
}
void main(){
  vec2 uv=gl_FragCoord.xy/u_res.y;
  vec3 c;
  if(u_kind==0)c=aurora(uv);else if(u_kind==1)c=dunas(uv);else if(u_kind==2)c=seda(uv);else c=orbita(uv);
  c+=(hash(gl_FragCoord.xy+0.5)-0.5)/150.0;
  gl_FragColor=vec4(c,1.0);
}
