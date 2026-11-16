#!/usr/bin/env python3
"""Gera prototipo/configuracao.html: prévia das telas da primeira configuração do Vitro.
Uso: python3 prototipo/gerar-configuracao.py launcher/assets/wallpapers/01a-leque.jpg prototipo/configuracao.html"""
import base64
import io
import json
import sys

from PIL import Image, ImageFilter

WALL, OUT = sys.argv[1], sys.argv[2]
im = Image.open(WALL).convert("RGB").resize((960, 540), Image.LANCZOS).filter(ImageFilter.GaussianBlur(14))
buf = io.BytesIO()
im.save(buf, "JPEG", quality=80)
bg = "data:image/jpeg;base64," + base64.b64encode(buf.getvalue()).decode()

STEPS = [
    {"t": "Vamos preparar a sua Fire TV",
     "b": ["O Vitro precisa de três autorizações suas e faz o resto sozinho: liga o botão Home, desativa os apps "
           "de anúncios e telemetria da Amazon e cuida do Prime Video.",
           "Leva uns 3 minutos. Nada é apagado, e tudo pode ser desfeito."],
     "k": ["Começar", "Agora não"]},
    {"t": "Ligue a depuração ADB",
     "b": ["É por ela que o Vitro faz os ajustes, de dentro da própria TV.",
           "1. Em Configurações › Minha Fire TV › Sobre, aperte OK 7 vezes sobre o nome do aparelho, até aparecer "
           "“Você agora é um desenvolvedor”.",
           "2. Volte uma tela, entre em Opções do desenvolvedor e ligue Depuração ADB. Depois, volte para o Vitro."],
     "k": ["Abrir as Configurações", "Já liguei"], "s": "Depuração ADB: desligada"},
    {"t": "Permita a VPN local",
     "b": ["O Fire OS não deixa um app falar com o próprio ADB pela rede comum. O Vitro abre uma VPN que só existe "
           "dentro da TV: nada sai dela, e a sua internet não passa por ela.",
           "No aviso a seguir, escolha OK."],
     "k": ["Permitir", "Voltar"]},
    {"t": "Autorize o Vitro no ADB",
     "b": ["Vai aparecer o aviso “Permitir depuração USB?” com a chave do Vitro. Marque “Sempre permitir deste "
           "computador” e escolha OK.",
           "A chave é criada na própria TV e nunca sai dela."],
     "k": ["Pedir autorização", "Voltar"], "s": "Esperando a sua resposta no aviso…"},
    {"t": "Desativar apps que você não usa",
     "b": ["Escolha uma opção. Nada é apagado: tudo volta em Configurações › Aplicativos. A Alexa e o controle por voz "
           "nunca são desativados."],
     "cards": [["Recomendados", "29 apps", "Anúncios, recomendações, telemetria (dados enviados à Amazon) e extras como "
                "modo de demonstração e tutoriais."],
               ["Recomendados e apps da Amazon", "34 apps", "Também Amazon Music, Amazon Photos, navegador Silk, guia de "
                "TV ao vivo e um atalho de diagnóstico."],
               ["Diagnóstico agora", "você escolhe", "O Vitro mede o que mais pesa na memória neste momento e mostra app "
                "por app, com os recomendados já marcados."]],
     "k": ["Pular"]},
    {"diag": True, "t": "O que mais pesa na sua TV agora",
     "b": ["Memória em uso medida agora. Os recomendados já vêm marcados; OK marca ou desmarca."],
     "rows": [
        ["sec", "Rodando agora"],
        ["lock", "Tela inicial da Amazon", "essencial do Fire OS", "152 MB", "sistema"],
        ["off", "Netflix", "instalado por você", "64 MB", "seu app"],
        ["lock", "Alexa", "controle por voz", "47 MB", "Alexa"],
        ["off", "Guia de TV ao vivo", "com.amazon.tv.livetv", "31 MB", "Amazon"],
        ["on", "Serviço de anúncios", "ofertas especiais", "26 MB", "recomendado"],
        ["on", "Prévias automáticas", "vídeos que tocam sozinhos na tela da Amazon", "22 MB", "recomendado"],
        ["on", "Reconhecimento de conteúdo", "registra o que passa na tela", "18 MB", "recomendado"],
        ["on", "Gerenciador de registros", "telemetria", "15 MB", "recomendado"],
        ["on", "Registro de eventos", "telemetria", "14 MB", "recomendado"],
        ["off", "Amazon Music", "com.amazon.bueller.music", "12 MB", "Amazon"],
        ["sec", "Recomendados parados agora (23)"],
        ["on", "Notícias", "com.amazon.hedwig", "parado", "recomendado"],
        ["on", "Amazon miniTV", "com.amazon.minitv.android.app", "parado", "recomendado"],
        ["on", "Relatórios de falha", "telemetria", "parado", "recomendado"],
     ],
     "k": ["Desativar 29 marcados", "Voltar"], "s": "29 marcados · em uso agora por eles: 95 MB"},
    {"t": "Prime Video só quando você usar",
     "b": ["O Prime Video é o app que mais pesa na memória da Fire TV. O Vitro pode deixá-lo desativado enquanto "
           "ninguém usa e ativá-lo na hora em que você abre pela barra do Vitro.",
           "Toda quarta-feira, à meia-noite, ele fica ativo por uma hora para receber atualizações."],
     "k": ["Sim, só quando eu usar", "Não, sempre ativo"]},
    {"t": "Último passo: o botão Home",
     "b": ["O Vitro liga o próprio serviço do botão Home e as permissões de que precisa, e reinicia.",
           "Depois disso, o Home do controle abre o Vitro."],
     "k": ["Concluir"]},
    {"t": "Tudo pronto",
     "b": ["Desativados: 25 apps (4 o Fire OS não deixa desativar).", "Prime Video: só quando você usar.",
           "O Vitro reinicia em instantes."],
     "k": []},
]

html = """<!doctype html>
<html lang="pt-BR">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Primeira configuração</title>
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap" rel="stylesheet">
<style>
  :root { --page: #0f1013; --text: #e3e4ea; --muted: #8d8f9b; --line: #2b2d36; }
  @media (prefers-color-scheme: light) { :root { --page: #f2f2ef; --text: #1f2027; --muted: #6a6c78; --line: #d9d9d3; } }
  * { box-sizing: border-box; }
  body { margin: 0; padding: 16px; background: var(--page); color: var(--text); font-family: "Inter", system-ui, sans-serif; }
  h1 { font-size: 20px; margin: 0 0 4px; }
  p.lead { margin: 0 0 14px; color: var(--muted); font-size: 14px; max-width: 760px; }
  #frame { position: relative; width: 100%; max-width: 1100px; aspect-ratio: 16/9; margin: 0 auto; overflow: hidden;
           border-radius: 8px; background: #000; }
  #ui { position: absolute; left: 0; top: 0; width: 960px; height: 540px; transform-origin: 0 0; color: #fff;
        background: url(BG) center/cover; }
  #ui::before { content: ""; position: absolute; inset: 0; background: rgba(8,10,16,.45); }
  .panel { position: absolute; left: 150px; top: 70px; width: 660px; height: 400px; border-radius: 30px; padding: 34px 40px;
           background: rgba(255,255,255,.10); border: 1px solid rgba(255,255,255,.22);
           box-shadow: 0 20px 60px rgba(0,0,0,.35), inset 0 1px 0 rgba(255,255,255,.18); }
  .step { font-size: 13px; letter-spacing: .08em; text-transform: uppercase; opacity: .7; margin-bottom: 10px; }
  .dots { position: absolute; right: 40px; top: 36px; display: flex; gap: 6px; }
  .dots i { width: 7px; height: 7px; border-radius: 50%; background: rgba(255,255,255,.3); }
  .dots i.on { background: #fff; }
  .title { font-size: 30px; font-weight: 600; margin: 0 0 14px; }
  .body p { font-size: 16px; line-height: 1.45; margin: 0 0 9px; opacity: .92; }
  .keys { position: absolute; left: 40px; bottom: 58px; display: flex; gap: 12px; flex-wrap: wrap; }
  .key { padding: 11px 22px; border-radius: 999px; font-size: 16px; font-weight: 500;
         background: rgba(255,255,255,.14); border: 1px solid rgba(255,255,255,.2); }
  .key.focus { background: #fff; color: #15171c; border-color: #fff; }
  .cards { display: flex; flex-direction: column; gap: 8px; margin-top: 6px; }
  .card { display: flex; gap: 14px; align-items: flex-start; padding: 10px 16px; border-radius: 16px;
          background: rgba(255,255,255,.07); border: 1px solid rgba(255,255,255,.14); }
  .card.focus { background: #fff; color: #15171c; }
  .card b { font-size: 16px; font-weight: 600; }
  .card .n { font-size: 13px; opacity: .7; margin-left: 6px; }
  .card div p { margin: 2px 0 0; font-size: 13.5px; line-height: 1.35; opacity: .85; }
  .panel.wide { left: 70px; top: 30px; width: 820px; height: 480px; }
  .panel.wide { display: flex; flex-direction: column; }
  .panel.wide .keys, .panel.wide .status { position: static; margin-top: 14px; }
  .panel.wide .status { margin-top: 10px; }
  .list { height: 214px; overflow: hidden; margin-top: 4px; border-radius: 14px; }
  .sec { font-size: 12px; letter-spacing: .07em; text-transform: uppercase; opacity: .6; margin: 8px 4px 4px; }
  .row { display: flex; align-items: center; gap: 12px; padding: 6px 12px; border-radius: 12px; height: 40px; }
  .row.focus { background: #fff; color: #15171c; }
  .row.lock { opacity: .5; }
  .box { width: 20px; height: 20px; border-radius: 6px; border: 2px solid currentColor; flex: none; display: grid;
         place-items: center; font-size: 14px; font-weight: 700; }
  .row.on .box { background: currentColor; }
  .row.on .box::after { content: "✓"; color: #15171c; }
  .row.on.focus .box::after { color: #fff; }
  .row.lock .box { border-style: dashed; }
  .row .nm { flex: 1; min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; font-size: 15px; }
  .row .nm small { font-size: 12.5px; opacity: .65; margin-left: 8px; }
  .row .mb { font-size: 14px; font-variant-numeric: tabular-nums; width: 64px; text-align: right; }
  .row .tag { font-size: 11.5px; padding: 2px 9px; border-radius: 99px; background: rgba(255,255,255,.14); width: 96px;
              text-align: center; }
  .row.focus .tag { background: rgba(0,0,0,.08); }
  .status { position: absolute; left: 40px; right: 40px; bottom: 24px; font-size: 13px; opacity: .7; }
  .hint { position: absolute; right: 22px; bottom: 14px; font-size: 12px; opacity: .55; }
  nav { display: flex; gap: 8px; justify-content: center; margin: 14px 0 0; flex-wrap: wrap; }
  nav button { font: inherit; font-size: 14px; padding: 8px 14px; border-radius: 8px; border: 1px solid var(--line);
               background: transparent; color: var(--text); cursor: pointer; }
  nav button.on { background: var(--text); color: var(--page); }
  @media (max-width: 600px) { body { padding: 16px; } }
</style>
</head>
<body>
<h1>Vitro: primeira configuração (prévia)</h1>
<p class="lead">Aparece só numa Fire TV que ainda não foi preparada. Na tela 5b, os números são um exemplo: na TV eles vêm da medição feita na hora. Navegação pelas setas e o OK do controle: o botão em
branco é o que está selecionado. Use os botões abaixo para ver cada tela.</p>
<div id="frame"><div id="ui">
  <div class="panel">
    <div class="step" id="step"></div>
    <div class="dots" id="dots"></div>
    <div class="title" id="title"></div>
    <div class="body" id="body"></div>
    <div class="keys" id="keys"></div>
    <div class="status" id="status"></div>
  </div>
  <div class="hint">Setas para escolher · OK para confirmar</div>
</div></div>
<nav id="nav"></nav>
<script>
const STEPS = DATA;
let cur = 0;
function show(i) {
  cur = i; const s = STEPS[i];
  document.getElementById('step').textContent = i === 0 ? 'Configuração do Vitro'
      : (i === STEPS.length - 1 ? 'Concluído' : s.diag ? 'Passo 4 de 6 · diagnóstico'
      : 'Passo ' + (i > 5 ? i - 1 : i) + ' de ' + (STEPS.length - 3));
  const pos = i > 5 ? i - 1 : (s.diag ? 4 : i);
  document.getElementById('dots').innerHTML = STEPS.slice(1).map((_, j) => '<i class="' + (j <= pos ? 'on' : '') + '"></i>').join('');
  document.getElementById('title').textContent = s.t;
  document.querySelector('.panel').classList.toggle('wide', !!(s.diag || s.cards));
  let body = s.b.map(p => '<p>' + p + '</p>').join('');
  if (s.cards) body += '<div class="cards">' + s.cards.map((c, j) => '<div class="card' + (j === 0 ? ' focus' : '')
      + '"><div><b>' + c[0] + '</b><span class="n">' + c[1] + '</span><p>' + c[2] + '</p></div></div>').join('') + '</div>';
  if (s.rows) body += '<div class="list">' + s.rows.map((r, j) => r[0] === 'sec' ? '<div class="sec">' + r[1] + '</div>'
      : '<div class="row ' + r[0] + (j === s.rows.findIndex(x => x[0] === 'on' || x[0] === 'off') ? ' focus' : '') + '"><span class="box"></span><span class="nm">' + r[1]
      + '<small>' + r[2] + '</small></span><span class="mb">' + r[3] + '</span><span class="tag">' + r[4] + '</span></div>').join('') + '</div>';
  document.getElementById('body').innerHTML = body;
  document.getElementById('keys').innerHTML = s.k.map((k, j) => '<span class="key' + (j === 0 && !s.cards && !s.rows ? ' focus' : '') + '">' + k + '</span>').join('');
  document.getElementById('status').textContent = s.s || '';
  document.querySelectorAll('nav button').forEach((b, j) => b.classList.toggle('on', j === i));
}
document.getElementById('nav').innerHTML = STEPS.map((s, i) => '<button>' + (s.diag ? '5b · diagnóstico' : (i > 5 ? i : i + 1)) + '</button>').join('');
document.querySelectorAll('nav button').forEach((b, i) => b.onclick = () => show(i));
function fit() { const f = document.getElementById('frame'); document.getElementById('ui').style.transform = 'scale(' + f.clientWidth / 960 + ')'; }
addEventListener('resize', fit); fit(); show(0);
</script>
</body>
</html>
"""
html = html.replace("BG", bg).replace("DATA", json.dumps(STEPS, ensure_ascii=False))
open(OUT, "w", encoding="utf-8").write(html)
print(OUT, len(html) // 1024, "KB")
