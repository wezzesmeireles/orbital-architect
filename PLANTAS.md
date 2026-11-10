# Plantas do Orbital Architect

Clique no altar com a mão vazia para abrir o planejador. A primeira construção de cada altar novo é o núcleo central. Depois, selecione uma planta, a sala de origem e uma de suas portas livres. O modelo 3D é exibido no catálogo assim que você seleciona a planta. As portas ocupadas ou incompatíveis ficam desativadas. **Revisar projeto** valida o local e abre a revisão do projeto. Use **Ver no mundo** para a projeção na posição real. Azul indica a geometria proposta, verde indica as portas da nova sala e amarelo indica o ponto de encaixe na sala existente. Use **Materiais** para conferir a lista antes de confirmar ou cancelar.


Medidas na ordem largura × altura × profundidade. Todas as plantas novas têm três blocos livres entre a laje de fundação e o piso principal. Cada galeria tem acesso por uma escada técnica e placas amarelas na altura dos pontos de passagem de cabos. Quando os painéis de duas galerias ficam alinhados, a revisão os marca em roxo. Selecione **Ligar galerias** para autorizar que a obra abra a passagem de um bloco depois da montagem. Uma proteção mantém os painéis no lugar e libera a opção de concluir a sala sem a ligação. A rocha-mãe serve de fundação quando está abaixo dos três blocos da galeria. Com autorização na revisão, os drones limpam o terreno. A obra em criativo dispensa itens e FE.

| Projeto | Tier | Dimensões externas | Espaço planejado |
| --- | --- | --- | --- |
| `hub`, `hub_t2`, `hub_t3`, `hub_t4` | 1 a 4 | 13×10×13 | Altar no centro, quatro portas laterais alinhadas e acabamento próprio de cada tier. |
| `starter` | 1 | 9×9×7 | Abrigo de pedra e madeira com janelas e entradas laterais. |
| `stone_module` | 2 | 9×9×7 | Variante de pedra com iluminação embutida. Usa o mesmo encaixe do abrigo para upgrade. |
| `greenhouse` | 2 | 15×11×15 | Canteiro elevado de 9×9, centro amarelo para o Pylon, calhas laterais marcadas em ciano e teto de vidro. |
| `logistics` | 2 | 19×10×15 | Quatro faixas de bancada e corredor transversal. |
| `ae2_core` | 3 | 19×10×19 | Fosso de 7×7, piso de circulação em volta e faixas laterais para Drives e Pattern Providers/Assemblers. |
| `reactor_bunker` | 3 | 25×27×31 | Área livre de 18×20×18 para **um** multibloco, corredor perimetral, parede dupla e antecâmara de observação com vidro escurecido. |
| `greenhouse_xl` | 3 | 45×17×45 | Nove canteiros de 9×9, caminhos entre eles e cobertura escalonada de vidro. |
| `ae2_core_xl` | 3 | 39×15×39 | Núcleo central com fosso, quatro setores de máquinas e distribuição pelo subsolo. |
| `logistics_xl` | 3 | 53×15×39 | Seis setores de 13×12 e porta de carga sul de 4×4. |
| `reactor_bunker_xl` | 3 | 61×31×55 | Duas baias livres de 18×20×18, corredor central e galeria de observação. |

Na estufa, o centro é uma marca removível. As calhas ficam vazias até serem abastecidas pelo jogador. No galpão, faça as descidas de cabo onde as bancadas precisarem delas. No bunker, amarelo marca o perímetro externo da área reservada à máquina. A antecâmara e o revestimento são elementos arquitetônicos. Eles não contêm radiação do Mekanism.

## Referências de dimensionamento

- O Harvester Pylon de **Pylons** atende uma área configurável de 3×3 a 9×9 e entrega itens a um inventário acima dele. O Harvester próprio da Mystical Agriculture atende 3×3 à sua frente. O canteiro reserva espaço para a configuração de Pylons. [Pylons](https://github.com/jeremiahwinsley/pylons) · [Mystical Agriculture](https://blakesmods.com/wiki/mysticalagriculture/blocks/harvester)
- No AE2 1.21.1, 7×7×7 é o limite da caixa que contém o Controller. O formato interno ainda precisa obedecer às regras de adjacência do mod. A galeria reserva espaço para a distribuição manual dos túneis P2P. [ME Controller](https://guide.appliedenergistics.org/1.21.1/items-blocks-machines/controller) · [Túneis P2P](https://guide.appliedenergistics.org/development/items-blocks-machines/p2p_tunnels)
- No Mekanism, a turbina chega a 17×18×17 e o reator de fissão a 18×18×18. A área livre acomoda qualquer um deles com dois blocos verticais adicionais. No Extreme Reactors, compare o tamanho desejado com a variante e a configuração do servidor antes de escolher o bunker. [Turbina](https://wiki.aidancbrady.com/wiki/Turbine) · [Fissão](https://wiki.aidancbrady.com/wiki/Fission_Reactor) · [Variantes do Extreme Reactors](https://github.com/ZeroNoRyouki/ExtremeReactors2/blob/master/src/main/java/it/zerono/mods/extremereactors/gamecontent/multiblock/reactor/variant/ReactorVariant.java)
- No SFM, os inventários precisam estar ligados ao Manager por cabos. Integrated Dynamics usa partes conectadas a uma rede de cabos. Por isso o galpão separa as bancadas do piso de manutenção e marca rotas para as duas redes. [Exemplos do SFM](https://github.com/TeamDman/SuperFactoryManager/blob/main/examples/README.md) · [Integrated Dynamics](https://integrateddynamics.rubensworks.net/)

## Referências de esquemáticos e construção

Comparei as imagens, as vistas 3D e as dimensões publicadas do Industrial Depot (55×16×41), do Workshop + Factory Building (70×52×80) e da Grand Victorian Greenhouse (150×53×122). O galpão XL adota a ordem de grandeza do Depot. O conservatório adapta a repetição das vigas de vidro a nove canteiros. Para a central de energia, as imagens de reator e turbina lado a lado orientaram a divisão em baias, e os limites do Mekanism determinaram a folga interna. [Industrial Depot](https://createmod.com/schematics/industrial-depot-factory) · [Workshop + Factory Building](https://createmod.com/schematics/workshop-factory-building) · [Grand Victorian Greenhouse](https://createmod.com/schematics/greenhousesqkk) · [Diagrama de reator e turbina](https://iwategame.blog.fc2.com/blog-entry-13.html)

Usei as estufas comunitárias *Greenhouse dome* e *Grand Victorian Greenhouse* como referência visual para cobertura de vidro e repetição de vigas. As medidas foram redesenhadas para um único canteiro técnico. Para o encaixe, usei o conceito de compartimentos, escotilhas e módulos de vidro de Subnautica. A documentação do Create serviu de referência para separar escolha, prévia e construção do esquema e listar materiais antes da execução. O guia de canais do AE2 inspirou a separação visual entre o módulo, a origem e a conexão ativa. As plantas técnicas do mod foram geradas localmente a partir das medidas acima. [Greenhouse dome](https://createmod.com/schematics/farming-dome) · [Grand Victorian Greenhouse](https://createmod.com/schematics/greenhousesqkk) · [Subnautica Seabases](https://unknownworlds.com/en/news/subnautica-seabases-released) · [Esquemáticos do Create](https://github.com/Creators-of-Create/Create/wiki/Loading-a-Schematic) · [Schematicannon](https://github.com/Creators-of-Create/Create/wiki/Schematicannon) · [Canais do AE2](https://guide.appliedenergistics.org/1.21/ae2-mechanics/channels/)

Os NBTs de todas as plantas podem ser regenerados com `node tools/generate-technical-rooms.mjs`. Eles contêm materiais estruturais vanilla e painéis texturizados do Orbital Architect, incluindo terra para cultivo e luzes. Máquinas e cabeamento são instalados pelo jogador. Nesta versão o catálogo libera projetos por tier. Os catalisadores específicos dos mods ainda precisam de implementação.









## Acabamento externo

- Faça uma faixa contínua de Parede Nervurada nos dois primeiros níveis da fachada. Acima dela, use Anteparas Cerâmicas e um grupo de visores conectados. Use a mesma altura de faixa nos módulos vizinhos.
- As portas vêm entre balizas de encaixe. Use luminárias lineares para corredores, beirais e caminhos.
- No teto, contorne a claraboia com Piso Técnico e deixe uma borda escura de um bloco ao redor. Preserve os pontos de encaixe superior e inferior quando a planta tiver esses pontos.
- Em terreno inclinado, use apoios sob os cantos e deixe a faixa de acesso livre para os módulos seguintes.

## Reforma do núcleo

As quatro variantes mantêm as portas no mesmo ponto em relação ao piso principal. Os frisos passam de âmbar para azul, ciano e violeta. O Tier 4 também reforça trechos do casco. O cartão abre uma prévia da reforma para conferência. Em sobrevivência, os novos blocos são cobrados na confirmação. A reforma atua apenas no que foi registrado como parte da estrutura. A galeria e as escadas são montadas na construção do núcleo novo.

## Escadas e portas

A Escada Técnica tem degrau 3D com textura própria. A receita rende quatro unidades com cinco pedras e um cobre na bancada.

Cada kit de porta monta todos os painéis de uma vez. Coloque o kit no canto inferior oeste de uma parede norte/sul, ou no canto inferior norte de uma parede leste/oeste. Deixe todo o retângulo livre. Clique no painel fechado para abrir e na moldura para fechar. As duas folhas deslizam para os lados com sons de motor e trava. A porta só fecha com a passagem livre e reabre se alguém entrar no vão durante o fechamento.

O kit 2×2 usa ferro, cobre, vidro e redstone. O kit 4×4 usa quatro kits 2×2, ferro e redstone. Os drones instalam as portas laterais e as escadas na obra nova. Em sobrevivência, a lista de materiais conta um kit por porta.

## Drones de construção

Cada altar tem um drone integrado do seu tier. Use o item de um drone em um altar ocioso para ampliar a frota. A aba **Frota** mostra os modelos 3D, os tiers e as vagas. Por ela, também é possível recolher um drone adicional. Os altares T1 a T4 comportam de dois a cinco drones e aceitam drones até seu próprio tier. Em construção, cada drone recolhe uma peça do estoque, voa até o ponto de montagem e a baixa com a garra. Os disparos na escavação viajam a 10, 17, 27 e 37 blocos por segundo conforme o tier. Em sobrevivência, todos os tiers usam 20 FE do altar por operação concluída. Em criativo, os drones trabalham sem consumir FE.

Coloque uma **Doca de Drones** a até 16 blocos do altar para vincular uma vaga de pouso. Use uma plataforma por drone e deixe dois blocos livres acima de cada uma. Conecte FE ao altar ou à doca para carregar a mesma reserva da frota. A iluminação da plataforma indica que ela está livre de obstrução e tem FE disponível. Na aba **Frota**, acompanhe os drones pousados e as docas operacionais.
