# Orbital Architect

Mod de construção modular para Minecraft Java 1.21.1. Planeje domos, corredores e galerias em 3D e use drones para montar a base com itens do estoque e energia FE.

## Instalação

Use Java 21 e NeoForge 21.1.265 ou uma versão posterior da série 21.1. Coloque o JAR do Orbital Architect na pasta `mods` do jogo. Para multiplayer, instale a mesma versão no cliente e no servidor. Applied Energistics 2 é opcional. Os drones aceitam FE de geradores compatíveis, incluindo Powah.

[GitHub Releases](https://github.com/wezzesmeireles/orbital-architect/releases) · [CurseForge](https://www.curseforge.com/minecraft/mc-mods/orbital-architect)

## Suporte

Use o GitHub Issues para relatar um bug ou sugerir uma mudança. Inclua as versões do mod, do NeoForge e do Minecraft e descreva como reproduzir o problema. [Abrir chamado](https://github.com/wezzesmeireles/orbital-architect/issues/new/choose) · [Lista de ideias e testes](ROADMAP.md)

## Desenvolvimento

```powershell
.\gradlew.bat build
.\gradlew.bat runClient
.\gradlew.bat runClient -PwithAe2 -PwithJei
.\gradlew.bat runClient -PwithAe2 -PwithJei -PwithJade -PwithPowah
```

O build grava o JAR em `build/libs`. As propriedades `withAe2` e `withJei` adicionam os mods opcionais ao ambiente de desenvolvimento. O JAR do Orbital Architect pode ser instalado com ou sem AE2.

## Teste no jogo

1. Coloque um Altar do Orbe no ponto que deve ser o centro do núcleo. Deixe espaço para a planta inicial de 25×25 ou para a cápsula de 19×19. O piso principal fica um bloco abaixo do altar e as escadas levam à galeria de cabos. Encoste um baú em uma das quatro faces laterais do altar.
2. Clique no altar com a mão vazia. A planta **Núcleo do Orbe** aparece em 3D assim que a tela abre. Clique em **Revisar projeto** para validar o local. A tela mostra o módulo e as portas. Use **Girar** ou **Ver no mundo** para inspecionar o encaixe.
3. Use **Corte** para ver o interior e **Vistoria** para localizar escavações, apoios sugeridos e blocos protegidos. Clique em **Fixar** para acompanhar os materiais pelo Terminal de Suprimentos enquanto abastece os baús. Autorize a limpeza na revisão se a obra indicar terreno removível.
4. Depois de construir o núcleo, escolha uma planta e a sala de origem. Clique em um ponto verde no modelo para selecionar uma eclusa livre. Em **Conexão e etapas**, escolha uma passarela de vidro, um túnel blindado ou uma galeria técnica. O desnível acrescenta degraus. As eclusas das plantas atuais usam vão de 4×4.
5. Em sobrevivência, conecte uma fonte de FE ao altar ou ao Terminal de Suprimentos. O terminal recebe FE por qualquer lado, exceto a face de saída. As outras faces também podem ler inventários vizinhos. Clique no terminal para consultar o estoque e ajustar a reserva de FE do núcleo. A partir do Tier 3, a frota também pode extrair materiais de uma rede ME ativa.
6. Para atualizar um abrigo básico, selecione-o como sala de origem e clique em **Upgrade da sala**. O upgrade mantém as portas e as posições do interior.
7. Use **Projetos → Conexão e etapas** para definir o corredor e a ordem da montagem. Em **Salas → Ajustes** você pode renomear a sala, sinalizar o caminho, salvar a planta ou pedir uma vistoria de reparo. Na Torre de Carga, clique na plataforma para subir e agache enquanto clica para descer. Itens soltos sobre a plataforma viajam junto.
8. Abra a aba **Salas** no Orbit ou pressione **U** dentro de uma sala. Selecione o destino e confira o custo em FE antes de clicar em **Teletransportar**. O mapa e o teleporte também ficam disponíveis durante a obra. O teleporte usa a energia acima da reserva e precisa de uma sala carregada com piso seguro.
9. Para reformar uma sala, abra **Salas → Trocar blocos** no altar. Escolha teto, piso ou paredes, selecione o novo material e revise o estoque e a prévia. Os drones entregam os blocos retirados na saída do terminal. A troca preserva claraboias, eclusas e alterações manuais.


Os drones recolhem uma peça que representa cada lote, carregam até a obra e baixam a garra para encaixá-la. Use **Acompanhar drones** para ver a tarefa, a carga e o destino. O painel do núcleo mostra a entrada e a saída em FE/s. A tela dos drones mostra o consumo de trabalho. Em criativo, a obra confirmada dispensa itens e FE. O progresso, a frota e os pontos de encaixe são salvos no altar.

| Drone | Montagem por bloco | Escavação por bloco | Lote de montagem em sobrevivência |
| --- | --- | --- | --- |
| T1 | 12 FE | 18 FE | 1 |
| T2 | 16 FE | 23 FE | 3 |
| T3 | 20 FE | 28 FE | 6 |
| T4 | 24 FE | 34 FE | 9 |

As taxas da tabela são as de fábrica. Uma troca soma desmontagem e montagem. O módulo de eficiência reduz o custo em 25%. O overclock aumenta a velocidade e o consumo. Os drones compartilham até 40 FE/t de recarga, em rodízio. Eles guardam os últimos 20% da bateria para voltar à doca e aguardam 100% antes de sair novamente. O drone integrado pousa no núcleo quando não tem uma doca própria. O painel distingue retorno, recarga e partida.

## Receitas para começar

- **Altar Tier 1** — um vidro em cima, cobre–carvão–cobre no meio e três pedregulhos embaixo. Carvão mineral ou vegetal funciona.
- **Piso Técnico** — três blocos de pedra comum na primeira linha e um cobre no centro da segunda, para oito pisos.
- **Luminária Orbital** — um vidro acima de cobre–carvão–cobre, para quatro luminárias.
- **Luminária Linear** — três vidros acima de cobre–carvão–cobre, para seis luminárias.

As demais peças aparecem no livro de receitas conforme você coleta os materiais e no JEI quando instalado. A cortadora de pedra continua disponível para conversões simples. Os upgrades usam cartões com receitas 100% vanilla.


## Cartões de upgrade

| Cartão | Materiais |
| --- | --- |
| T2 · Engenheiro | 2 cobres, 2 ferros, 2 redstones e 1 papel. |
| T3 · Rede ME | 2 ouros, 2 diamantes, 2 cobres, 1 fragmento de ametista, 1 redstone e 1 papel. |
| T4 · Protótipo | 2 obsidianas, 3 fragmentos de ametista, 1 fragmento de eco, 1 lingote de netherita, 1 redstone e 1 papel. |

Os cartões avançam um tier por vez. O altar mantém orientação, energia e registro das salas. Se o núcleo já foi construído, o painel abre a revisão da reforma. Confirme para aplicar a faixa de acabamento do novo tier. Você pode adiar e voltar por **Reformar núcleo**. A instalação consome o cartão em sobrevivência. O criativo preserva o cartão. O AE2 é opcional para usar a rede ME e não é necessário para fabricar nenhum cartão.


## Altares

| Tier | Visual | Uso |
| --- | --- | --- |
| 1 · Pioneiro | Pedestal de cobre e cristal âmbar | Usa inventários adjacentes e recebe FE. |
| 2 · Engenheiro | Pedestal claro e cristal azul com um anel | Usa FE e libera projetos de Tier 2. |
| 3 · Rede ME | Pedestal de grafite e cristal ciano com dois anéis | Usa FE e pode extrair materiais do ME quando AE2 está instalado. |
| 4 · Protótipo | Pedestal reforçado e cristal violeta com três anéis | Disponível para teste no criativo. Void Pocket permanece em desenvolvimento. |

O planejador usa as texturas de cobre e piso do mod. A faixa superior e os botões de destaque seguem a cor do altar.


## Projetos de sala

Os projetos estão em `data/orbital_architect/orbital_rooms`. Um datapack pode adicionar projetos ao catálogo ou substituir os projetos embutidos. Consulte [Plantas e referências](PLANTAS.md) para ver as dimensões, os espaços técnicos e como selecionar uma planta. Cada JSON aponta para um `StructureTemplate` em `data/<namespace>/structure/<nome>.nbt`, informa o tier mínimo e declara portas por direção em coordenadas locais, com a origem em `[0, 0, 0]`. Uma porta deve apontar para uma abertura na face externa do volume. O upgrade deve manter as mesmas coordenadas das portas e substitui os estados ainda correspondentes ao registro estrutural nas coordenadas compartilhadas pelos dois projetos. Uma troca manual pelo mesmo estado de bloco é indistinguível no registro.

O leitor aceita uma única paleta de materiais estruturais vanilla e painéis do próprio mod explicitamente permitidos, sem entidades nem dados de blocos com inventário. Cada bloco colocado exige a extração de um item correspondente. Líquidos, blocos indestrutíveis e blocos com entidades impedem a limpeza. As obras novas usam os eventos de quebra e colocação do NeoForge para respeitar a autorização do servidor. A obra fica pausada se o responsável sair da dimensão ou perder o modo criativo durante uma obra criativa. A Luminária Orbital, a Luminária Linear e a Baliza de Encaixe emitem luz 15 e usam texturas animadas. A cortadora de pedra converte a Luminária Orbital em qualquer uma das outras duas.

O planejador tem catálogo, modelo 3D com vista em corte e holograma no mundo. O Piso Técnico se conecta a placas e luminárias compatíveis. Anteparas e Visores Estruturais se conectam a blocos iguais. Para salas existentes, selecione a sala e use **Atualizar junções**. Se o núcleo antigo tiver o altar perto da entrada, selecione-o e use **Centralizar altar**. O centro precisa de piso e espaço livre. Os baús e cabos permanecem nos seus lugares. O retrofit de um núcleo antigo atualiza os acabamentos registrados. A galeria subterrânea vem nos núcleos construídos com a planta nova. As plantas usam anteparas, paredes nervuradas, piso técnico, visores e casco reforçado, produzidos em bancada ou na cortadora de pedra. O orbe flutua em 3D e tem dois anéis com rotações independentes. Catalisadores por projeto, portas de conduíte, validação no ATM10 completo e Void Pocket ficam para etapas posteriores. O Tier 4 está registrado para desenvolvimento. Mundos existentes mantêm as salas e os trabalhos gravados. Para iniciar uma base com o núcleo central novo, instale um altar em outro local.

















