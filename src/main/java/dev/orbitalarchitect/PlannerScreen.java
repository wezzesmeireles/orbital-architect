package dev.orbitalarchitect;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.network.PacketDistributor;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;

final class PlannerScreen extends Screen {
    private static final int PANEL_WIDTH = 310;
    private static final int PANEL_HEIGHT = 232;
    private static final int DIVIDER = OrbitUi.BORDER;
    private static final int SUBTLE = OrbitUi.MUTED;
    private static final int TEXT = OrbitUi.TEXT;
    private static final int MATERIAL_ROW = 19;
    private static final int PAGE_SIZE = 5;
    private final BlockPos altar;
    private final List<ItemStack> droneIcons = OrbitalArchitect.DRONES.stream()
            .map(item -> new ItemStack(item.get())).toList();
    private CompoundTag data;
    private int page;
    private int filter;
    private int stockOffset;
    private int progressTimer;
    private int selected;
    private int room;
    private Direction face = Direction.NORTH;
    private int left;
    private int top;
    private boolean showCosts;
    private boolean showFleet;
    private boolean showOperations, showMap, showSurvey, catalogCutaway, pillars;
    private int grade, roomColor;
    private EditBox roomName;
    private CorridorPlan.Style corridor = CorridorPlan.Style.DIRECT;
    private CorridorPlan.Priority priority = CorridorPlan.Priority.SHELL;
    private static final String[] ROOM_COLORS = {"Verde", "Azul", "Âmbar", "Violeta", "Coral"};
    private boolean cutaway;
    private boolean clearObstructions;
    private boolean connectGallery;
    private int selectedDrone;
    private float displayedCharge;
    private float displayedProgress;
    private float displayedCore = -1;
    private long viewChangedAt = Util.getMillis();
    private long catalogChangedAt = viewChangedAt;
    private String notice = "";
    private long noticeUntil;
    private int rotation;
    private PlannerModel model;
    private CompoundTag modeledReview;
    private int modeledRotation;
    private boolean modeledCutaway, modeledSurvey;
    private PlannerModel catalogModel;
    private CompoundTag catalogGeometry;
    private final CompoundTag materials;
    private CompoundTag materialsBeforeEditor;
    private boolean showMaterials;
    private boolean renovateMaterials;
    private Button reviewSwap;
    private boolean materialCutaway;
    private MaterialGroup materialGroup = MaterialGroup.STRUCTURE;
    private DomePalette.Part materialPart = DomePalette.Part.WALL;
    private String materialQuery = "";
    private int materialOffset;
    private int catalogRotation;
    private record MaterialChoice(Block block, String label, String id, String search) {}
    private final EnumMap<DomePalette.Part, List<MaterialChoice>> availableMaterials =
            new EnumMap<>(DomePalette.Part.class);
    private List<MaterialChoice> filteredMaterials = List.of();
    private final List<Button> materialRows = new ArrayList<>();
    private int editorWidth;
    private int editorHeight;
    private int resultsX;
    private int resultsWidth;
    private int viewportX;
    private int viewportWidth;
    private boolean browsePending = true;
    private boolean awaitingBrowse;
    private long browseRequestedAt;
    private CompoundTag mappedSnapshot;
    private List<MapNode> mappedNodes = List.of();
    private static final Direction[] PORTS = {Direction.NORTH, Direction.SOUTH, Direction.EAST,
            Direction.WEST, Direction.UP, Direction.DOWN};
    private static final String[] PORT_LABELS = {"N", "S", "L", "O", "↑", "↓"};

    private enum MaterialGroup {
        STRUCTURE("Estrutura", DomePalette.Part.WALL, DomePalette.Part.FLOOR, DomePalette.Part.ROOF,
                DomePalette.Part.GLASS),
        FINISH("Acabamento", DomePalette.Part.TRIM, DomePalette.Part.DETAIL, DomePalette.Part.STAIRS,
                DomePalette.Part.DOOR, DomePalette.Part.PLANTER),
        LIGHTING("Iluminação", DomePalette.Part.LIGHT, DomePalette.Part.SIGNAL);

        private final String label;
        private final List<DomePalette.Part> parts;

        MaterialGroup(String label, DomePalette.Part... parts) {
            this.label = label;
            this.parts = List.of(parts);
        }
    }

    PlannerScreen(BlockPos altar, CompoundTag data) {
        super(Component.literal("Orbital Architect"));
        this.altar = altar;
        this.data = data;
        setNotice(data.getString("Message"));
        materials = data.getCompound("PreferredPalette").copy();
        if (travelOnly()) {
            showOperations = showMap = true;
            room = Math.max(0, data.getInt("CurrentRoom"));
        }
        if (data.contains("FocusDrone")) focusDrone(data.getInt("FocusDrone"));
    }

    BlockPos altar() { return altar; }
    boolean travelOnly() { return data.getBoolean("TravelOnly"); }

    void update(CompoundTag snapshot) {
        boolean stateChanged = !data.getString("State").equals(snapshot.getString("State"));
        boolean workControlsChanged = data.contains("Blocked") != snapshot.contains("Blocked")
                || data.getBoolean("CanSkipGallery") != snapshot.getBoolean("CanSkipGallery")
                || data.getBoolean("CanConfirm") != snapshot.getBoolean("CanConfirm");
        if (stateChanged) {
            clearObstructions = false;
            connectGallery = false;
            stockOffset = 0;
            showCosts = false;
            showOperations = showMap;
            showSurvey = snapshot.getLongArray("SiteRepair").length > 0;
            displayedProgress = 0;
            renovateMaterials = false;
            closeReviewModel();
            viewChangedAt = Util.getMillis();
        }
        boolean sameRoster = Arrays.equals(data.getIntArray("DroneRoster"), snapshot.getIntArray("DroneRoster"));
        if (showMap && !snapshot.contains("Rooms") && data.contains("Rooms")) copyRoomNavigation(data, snapshot);
        data = snapshot;
        setNotice(snapshot.getString("Message"));
        if (snapshot.contains("Browse") || stateChanged) awaitingBrowse = false;
        if (snapshot.contains("FocusDrone")) focusDrone(snapshot.getInt("FocusDrone"));
        else if (showFleet && !stateChanged && sameRoster && snapshot.getString("Message").isEmpty()
                && !snapshot.contains("Browse")) return;
        if (snapshot.contains("Browse")) {
            var browse = snapshot.getCompound("Browse");
            if (!browsePending) {
                catalogGeometry = browse.contains("Ghost") ? browse : null;
                rebuildCatalogModel();
            }
            if (!stateChanged) return;
        } else if (snapshot.getString("State").equals("IDLE")) {
            if (!travelOnly()) scheduleBrowse();
        } else {
            showMaterials = false;
            if (!stateChanged && isWorking() && !workControlsChanged) return;
        }
        rebuildWidgets();
    }

    void updateTelemetry(CompoundTag readings) { data.merge(readings); }

    void updateRoomNavigation(CompoundTag reading) {
        copyRoomNavigation(reading, data);
        mappedSnapshot = null;
        room = Math.clamp(room, 0, Math.max(0, data.getList("Rooms", Tag.TAG_COMPOUND).size() - 1));
        navigate(true, false);
    }

    private static void copyRoomNavigation(CompoundTag from, CompoundTag to) {
        for (String key : List.of("Rooms", "RoomLinks", "CurrentRoom", "TravelCosts")) {
            var value = from.get(key);
            if (value != null) to.put(key, value);
        }
    }

    @Override
    public void tick() {
        String phase = data.getString("State");
        progressTimer++;
        if (showFleet || isWorking()) {
            if (progressTimer % 10 == 0) {
                if (showFleet) requestDrone(PlannerNetwork.REFRESH, 0);
                else request(PlannerNetwork.REFRESH, "");
            }
            return;
        }
        if (progressTimer % 20 == 0) request(PlannerNetwork.TELEMETRY, travelOnly() ? "map" : "");
        if (showMap && !showMaterials || renovateMaterials) return;
        if (awaitingBrowse && Util.getMillis() - browseRequestedAt > 2500) {
            awaitingBrowse = false;
            browsePending = true;
        }
        if (showFleet || !browsePending || awaitingBrowse || !phase.equals("IDLE")) return;
        var plans = visiblePlans();
        if (plans.isEmpty()) return;
        browsePending = false;
        awaitingBrowse = true;
        browseRequestedAt = Util.getMillis();
        request(PlannerNetwork.BROWSE, plans.get(selected).getString("Id"));
    }

    private void scheduleBrowse() {
        browsePending = true;
    }

    private void rebuildCatalogModel() {
        if (catalogModel != null) catalogModel.close();
        catalogModel = catalogGeometry == null ? null
                : new PlannerModel(catalogGeometry, catalogRotation, showMaterials ? materialCutaway : catalogCutaway);
        catalogChangedAt = Util.getMillis();
    }

    private void closeReviewModel() {
        if (model != null) model.close();
        model = null;
        modeledReview = null;
    }

    private void prepareReviewModel() {
        if (modeledReview == data && modeledRotation == rotation && modeledCutaway == cutaway && modeledSurvey == showSurvey) return;
        closeReviewModel();
        model = new PlannerModel(data, rotation, cutaway, showSurvey);
        modeledReview = data;
        modeledRotation = rotation;
        modeledCutaway = cutaway;
        modeledSurvey = showSurvey;
        viewChangedAt = Util.getMillis();
    }

    @Override public void removed() {
        closeReviewModel();
        if (catalogModel != null) catalogModel.close();
        catalogModel = null;
        super.removed();
    }

    private void selectChanged() {
        scheduleBrowse();
        rebuildWidgets();
    }

    private void navigation() {
        if (travelOnly()) {
            control(12, 22, 102, 14, "Mapa de salas", OrbitUi.Role.TAB, OrbitUi.Icon.MAP, true, () -> {});
            button(240, 5, 58, 13, "Fechar", this::onClose);
            return;
        }
        boolean local = minecraft.player != null && minecraft.player.canInteractWithBlock(altar, 1);
        boolean rooms = data.getInt("CompletedRooms") > 0 || !data.getList("Rooms", Tag.TAG_COMPOUND).isEmpty();
        String projects = switch (data.getString("State")) {
            case "PREVIEW", "INSPECT" -> "Revisão";
            case "IDLE" -> "Projetos";
            default -> "Obra em curso";
        };
        control(12, 22, 92, 14, projects, OrbitUi.Role.TAB, OrbitUi.Icon.PROJECT, !showMap && !showFleet,
                () -> navigate(false, false)).active = local;
        control(109, 22, 92, 14, "Salas", OrbitUi.Role.TAB, OrbitUi.Icon.MAP, showMap,
                () -> {
                    if (data.getList("Rooms", Tag.TAG_COMPOUND).isEmpty()) request(PlannerNetwork.OPEN_TRAVEL, "panel");
                    else navigate(true, false);
                }).active = local && rooms;
        control(206, 22, 92, 14, "Drones " + data.getInt("Drones") + "/" + data.getInt("DroneCapacity"),
                OrbitUi.Role.TAB, OrbitUi.Icon.DRONE, showFleet, () -> navigate(false, true));
    }

    private void navigate(boolean map, boolean fleet) {
        if (showMap == map && showFleet == fleet && showOperations == map) return;
        showMap = showOperations = map;
        showFleet = fleet;
        viewChangedAt = Util.getMillis();
        rebuildWidgets();
    }

    @Override
    protected void init() {
        if (showMaterials && !showFleet && data.getString("State").equals("IDLE")) {
            editorWidth = Math.min(590, width - 16);
            editorHeight = Math.min(290, height - 16);
            left = (width - editorWidth) / 2;
            top = (height - editorHeight) / 2;
            resultsX = 101;
            resultsWidth = Math.max(82, editorWidth * 29 / 100);
            viewportX = resultsX + resultsWidth + 10;
            viewportWidth = editorWidth - viewportX - 12;
            materialEditor();
            return;
        }
        left = (width - PANEL_WIDTH) / 2;
        top = (height - PANEL_HEIGHT) / 2;
        navigation();
        if (showMap) {
            mapControls();
            return;
        }
        if (showOperations && !showFleet && data.getString("State").equals("IDLE")) {
            operations();
            return;
        }
        if (showFleet) {
            fleetControls();
            return;
        }
        switch (data.getString("State")) {
            case "IDLE" -> {
                catalog();
                int next = data.getInt("NextTier");
                if (next > 0) {
                    boolean hasCard = data.getBoolean("HasCard");
                    if (hasCard) primary(236, 5, 62, 13, "Instalar T" + next, () -> request(PlannerNetwork.INSTALL_CARD, ""));
                }
            }
            case "PREVIEW", "INSPECT" -> {
                prepareReviewModel();
                boolean valid = data.getString("State").equals("PREVIEW");
                boolean owner = data.getBoolean("CanConfirm");
                if (!valid) showCosts = false;
                int obstructions = data.getInt("Obstructions");
                control(12, 146, 60, 20, "Girar", OrbitUi.Role.CONTROL, OrbitUi.Icon.ROTATE, false,
                        () -> { showCosts = false; rotation = (rotation + 1) % 4; rebuildWidgets(); });
                control(77, 146, 65, 20, cutaway ? "Exterior" : "Corte", OrbitUi.Role.CONTROL, OrbitUi.Icon.CUT, cutaway,
                        () -> { showCosts = false; cutaway = !cutaway; rebuildWidgets(); });
                control(147, 146, 68, 20, showCosts ? "Vista 3D" : "Materiais", OrbitUi.Role.CONTROL, OrbitUi.Icon.LIST, showCosts,
                        () -> { showCosts = !showCosts; rebuildWidgets(); }).active = valid;
                button(220, 146, 78, "Ver no mundo", this::onClose).active = valid || data.contains("Blocked");
                if (valid && !data.getBoolean("Creative")) {
                    button(259, 41, 39, 13, "Fixar", () -> request(PlannerNetwork.PIN, ""))
                            .setTooltip(Tooltip.create(Component.literal("Fixar a lista no Terminal de Suprimentos")));
                }
                button(12, 115, 57, 13, showSurvey ? "Ocultar" : "Vistoria", () -> {
                    showSurvey = !showSurvey;
                    showCosts = false;
                    rebuildWidgets();
                }).visible = !showCosts;
                if (valid && obstructions > 0) {
                    var clear = button(12, 169, hasGalleryLink() ? 138 : 286, (clearObstructions ? "[x] " : "[ ] ")
                                    + "Limpar " + obstructions + " blocos",
                            () -> { clearObstructions = !clearObstructions; rebuildWidgets(); });
                    clear.active = owner;
                    clear.setTooltip(Tooltip.create(Component.literal("Autoriza remover os obstáculos no volume da obra.")));
                }
                if (valid && hasGalleryLink()) {
                    var link = button(obstructions > 0 ? 154 : 12, 169, obstructions > 0 ? 144 : 286,
                            (connectGallery ? "[x] " : "[ ] ") + "Ligar galerias",
                            () -> { connectGallery = !connectGallery; rebuildWidgets(); });
                    link.active = owner;
                    link.setTooltip(Tooltip.create(Component.literal(
                            "Autoriza retirar os dois painéis marcados em roxo depois da montagem.")));
                }
                button(12, 192, 102, valid ? "Voltar" : "Fechar aviso",
                        () -> request(valid ? PlannerNetwork.CANCEL : PlannerNetwork.REFRESH, "")).active = !valid || owner;
                int lift = data.getInt("RaiseHub");
                if (!valid && lift > 0) {
                    primary(120, 192, 178, 20, "Elevar altar +" + lift,
                            () -> request(PlannerNetwork.RAISE_HUB, ""))
                            .setTooltip(Tooltip.create(Component.literal("Sobe o altar e abre uma nova prévia para revisar.")));
                    break;
                }
                String confirmLabel = data.contains("Renovation") ? "Confirmar troca" : data.getBoolean("Repair")
                        ? "Confirmar reparo" : data.getInt("HubRetrofitTier") > 0
                        ? "Aplicar reforma T" + data.getInt("HubRetrofitTier")
                        : data.getBoolean("Creative") ? "Construir no criativo" : "Confirmar construção";
                primary(120, 192, 178, 20, confirmLabel,
                        () -> request(PlannerNetwork.CONFIRM, "")).active = valid && owner
                        && (obstructions == 0 || clearObstructions);
            }
            default -> {
                button(12, 127, 134, "Acompanhar drones", () -> { showFleet = true; rebuildWidgets(); });
                if (data.contains("Blocked")) button(154, 127, 144, "Ver bloqueio no mundo", this::onClose);
                if (data.getBoolean("CanSkipGallery")) {
                    button(12, 192, 160, "Concluir sem ligar galerias",
                            () -> request(PlannerNetwork.SKIP_GALLERY, "")).active = data.getBoolean("CanConfirm");
                    button(178, 192, 120, "Fechar", this::onClose);
                } else {
                    button(85, 192, 140, "Fechar", this::onClose);
                }
            }
        }
    }

    private void catalog() {
        var plans = visiblePlans();
        var rooms = data.getList("Rooms", Tag.TAG_COMPOUND);
        int pages = Math.max(1, (plans.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.min(page, pages - 1);
        selected = Math.max(0, Math.min(selected, plans.size() - 1));
        room = Math.max(0, Math.min(room, rooms.size() - 1));
        boolean filters = data.getList("Catalog", Tag.TAG_COMPOUND).size() > 1;
        if (filters) {
            for (int i = 0; i < 3; i++) {
                int choice = i;
                String name = switch (i) { case 1 -> "Normais"; case 2 -> "XL"; default -> "Todas"; };
                control(12 + i * 44, 55, 42, 16, name, OrbitUi.Role.TAB, null, filter == i,
                        () -> { filter = choice; page = 0; selected = 0; selectChanged(); })
                        .setTooltip(Tooltip.create(Component.literal(name)));
            }
        }
        int firstRow = filters ? 76 : 53;
        for (int i = page * PAGE_SIZE; i < Math.min(plans.size(), (page + 1) * PAGE_SIZE); i++) {
            int index = i;
            var plan = plans.get(i);
            boolean locked = !data.getBoolean("Creative") && plan.getInt("Tier") > data.getInt("Tier");
            String label = (selected == i ? "» " : "") + (locked ? "T" + plan.getInt("Tier") + " · " : "")
                    + plan.getString("Name");
            materialRow(12, firstRow + 17 * (i % PAGE_SIZE), 130, 16, label,
                    () -> {
                        selected = index;
                        if (plan.contains("Palette")) changePalette(plan.getCompound("Palette"));
                        selectChanged();
                    })
                    .setTooltip(Tooltip.create(Component.literal(plan.getString("Name") + " · "
                            + plan.getString("Size") + " · Tier " + plan.getInt("Tier")
                            + (plan.getInt("Airlock") == 4 ? "\nEclusas 4×4" : ""))));
        }
        if (pages > 1) {
            button(12, 162, 24, 14, "‹", () -> { page--; selected = page * PAGE_SIZE; selectChanged(); }).active = page > 0;
            button(118, 162, 24, 14, "›", () -> { page++; selected = page * PAGE_SIZE; selectChanged(); }).active = page + 1 < pages;
        }
        button(12, 178, 130, 13, "Conexão e etapas", () -> { showOperations = true; rebuildWidgets(); });
        var editor = button(151, 128, 147, 15, "Editar materiais", () -> {
            materialsBeforeEditor = materials.copy();
            showMaterials = true;
            rebuildCatalogModel();
            rebuildWidgets();
        });
        editor.active = !plans.isEmpty();
        editor.setTooltip(Tooltip.create(Component.literal("Troque paredes, piso, portas e luzes na prévia 3D.")));

        var anchor = rooms.getCompound(room);
        var plan = plans.isEmpty() ? new CompoundTag() : plans.get(selected);
        int ports = compatiblePorts(anchor.getInt("Ports"), plan.getInt("Doors"));
        if ((ports & 1 << face.ordinal()) == 0 && ports != 0) {
            face = Direction.values()[Integer.numberOfTrailingZeros(ports)];
        }
        if (!rooms.isEmpty()) {
            button(151, 144, 19, "‹", () -> { room--; selectChanged(); }).active = room > 0;
            button(279, 144, 19, "›", () -> { room++; selectChanged(); }).active = room + 1 < rooms.size();
            for (int i = 0; i < PORTS.length; i++) {
                var port = PORTS[i];
                var control = button(151 + i * 25, 163, 22, 14, port == face ? "•" + PORT_LABELS[i] : PORT_LABELS[i],
                        () -> { face = port; selectChanged(); });
                control.active = (ports & 1 << port.ordinal()) != 0;
                control.setTooltip(Tooltip.create(Component.literal(directionName(port)
                        + (control.active ? "" : " · ocupada ou incompatível"))));
            }
            if (anchor.getBoolean("Hub") && data.getBoolean("CanCenter")) {
                button(12, 192, 130, "Centralizar altar", () -> request(PlannerNetwork.CENTER, ""))
                        .setTooltip(Tooltip.create(Component.literal("Move o altar e mantém os projetos. Baús e cabos ficam no local atual.")));
            } else if (anchor.getBoolean("Hub") && data.getBoolean("HubUpgrade")) {
                button(12, 192, 130, "Reformar núcleo", () -> request(PlannerNetwork.UPGRADE_HUB, ""))
                        .setTooltip(Tooltip.create(Component.literal("Atualiza o núcleo para o próximo tier, mantendo o interior.")));
            } else if (anchor.getBoolean("Upgradable") && (data.getBoolean("Creative") || data.getInt("Tier") >= 2)) {
                button(12, 192, 130, "Melhorar sala de origem",
                        () -> request(PlannerNetwork.UPGRADE, "orbital_architect:stone_module"));
            } else {
                button(12, 192, 130, "Atualizar junções", () -> request(PlannerNetwork.UPDATE_JOINS, ""));
            }
        }
        control(151, 178, 70, 13, "Girar", OrbitUi.Role.CONTROL, OrbitUi.Icon.ROTATE, false, () -> {
            catalogRotation = (catalogRotation + 1) % 4;
            rebuildCatalogModel();
        }).active = !plans.isEmpty();
        control(226, 178, 72, 13, catalogCutaway ? "Exterior" : "Corte", OrbitUi.Role.CONTROL, OrbitUi.Icon.CUT, catalogCutaway, () -> {
            catalogCutaway = !catalogCutaway;
            rebuildCatalogModel();
            rebuildWidgets();
        }).active = !plans.isEmpty();
        primary(151, 192, 147, 20, "Revisar projeto",
                () -> request(PlannerNetwork.PREVIEW, plan.getString("Id")))
                .active = !plans.isEmpty() && (data.getBoolean("Creative") || plan.getInt("Tier") <= data.getInt("Tier"))
                        && (rooms.isEmpty() || ports != 0);
    }

    private void operations() {
        button(243, 5, 55, 13, "Voltar", () -> navigate(false, false));
        button(12, 56, 140, "Ligação · " + corridor.label, () -> {
            corridor = corridor.next();
            selectChanged();
        });
        button(158, 56, 140, "Desnível · " + (grade > 0 ? "+" : "") + grade + " bloco", () -> {
            if (corridor == CorridorPlan.Style.DIRECT) corridor = CorridorPlan.Style.GLASS;
            grade = grade == 1 ? -1 : grade + 1;
            selectChanged();
        });
        button(12, 80, 140, priority.label, () -> { priority = priority.next(); selectChanged(); });
        button(158, 80, 140, "Pilares · " + (pillars ? "Instalar" : "Só sugerir"), () -> {
            pillars = !pillars;
            selectChanged();
        });
        button(12, 104, 140, catalogCutaway ? "Exibir exterior" : "Cortar prévia 3D", () -> {
            catalogCutaway = !catalogCutaway;
            rebuildCatalogModel();
            rebuildWidgets();
        });
        button(158, 104, 140, "Girar prévia 3D", () -> {
            catalogRotation = (catalogRotation + 1) % 4;
            rebuildCatalogModel();
        });
        var rooms = data.getList("Rooms", Tag.TAG_COMPOUND);
        if (rooms.isEmpty()) return;
        button(231, 125, 17, 13, "‹", () -> { room--; selectChanged(); }).active = room > 0;
        button(281, 125, 17, 13, "›", () -> { room++; selectChanged(); }).active = room + 1 < rooms.size();
        var selectedRoom = rooms.getCompound(room);
        roomColor = Math.clamp(selectedRoom.getInt("Color"), 0, ROOM_COLORS.length - 1);
        roomName = addRenderableWidget(new EditBox(font, left + 12, top + 143, 126, 17, Component.literal("Nome da sala")));
        roomName.setMaxLength(32);
        roomName.setValue(selectedRoom.getString("Name"));
        addRenderableWidget(Button.builder(Component.literal(ROOM_COLORS[roomColor]), control -> {
            roomColor = (roomColor + 1) % ROOM_COLORS.length;
            control.setMessage(Component.literal(ROOM_COLORS[roomColor]));
        }).bounds(left + 144, top + 143, 55, 17).build());
        button(205, 143, 93, 17, "Salvar", () -> PacketDistributor.sendToServer(new PlannerNetwork.Request(
                altar, PlannerNetwork.EDIT_ROOM, roomName.getValue(), room, roomColor, false, false, new CompoundTag())));
        button(12, 166, 139, 17, "Salvar planta", () -> request(PlannerNetwork.SAVE_PERSONAL, ""));
        button(157, 166, 141, 17, "Aplicar paleta salva", () -> changePalette(data.getCompound("FavoritePalette")));
        button(12, 189, 139, 17, "Guiar até esta sala", () -> request(PlannerNetwork.GUIDE, ""));
        button(157, 189, 141, 17, "Vistoriar para reparo", () -> request(PlannerNetwork.REPAIR, ""));
    }

    private void mapControls() {
        if (!travelOnly() && data.getString("State").equals("IDLE")) {
            button(240, 5, 58, 13, "Ajustes", () -> { showMap = false; rebuildWidgets(); });
            button(12, 189, 139, 17, "Trocar blocos", this::openRenovation);
        }
        int rooms = data.getList("Rooms", Tag.TAG_COMPOUND).size();
        button(12, 166, 20, 14, "‹", () -> { room--; rebuildWidgets(); }).active = room > 0;
        button(35, 166, 20, 14, "›", () -> { room++; rebuildWidgets(); }).active = room + 1 < rooms;
        var travel = control(157, 189, 141, 17, "Teletransportar", OrbitUi.Role.PRIMARY, OrbitUi.Icon.TARGET, false,
                () -> request(PlannerNetwork.TELEPORT, travelOnly() ? "map" : ""));
        travel.active = data.contains("CurrentRoom") && room != data.getInt("CurrentRoom");
        travel.setTooltip(Tooltip.create(Component.literal("Usa energia do núcleo acima da reserva.")));
    }

    private void openRenovation() {
        materialsBeforeEditor = materials.copy();
        materials.getAllKeys().stream().toList().forEach(materials::remove);
        materialPart = DomePalette.Part.ROOF;
        materialQuery = "";
        materialOffset = 0;
        renovateMaterials = showMaterials = true;
        rebuildWidgets();
    }

    private record MapNode(int x, int y) {}

    private List<MapNode> mapNodes() {
        if (mappedSnapshot == data) return mappedNodes;
        mappedSnapshot = data;
        var rooms = data.getList("Rooms", Tag.TAG_COMPOUND);
        var world = rooms.stream().map(tag -> BlockPos.of(((CompoundTag) tag).getLong("Center"))).toList();
        if (world.isEmpty()) return mappedNodes = List.of();
        var core = world.getFirst();
        var flat = world.stream().map(pos -> new MapNode(pos.getX(), pos.getZ() - (pos.getY() - core.getY()) / 2)).toList();
        int minX = flat.stream().mapToInt(MapNode::x).min().orElse(0), maxX = flat.stream().mapToInt(MapNode::x).max().orElse(0);
        int minY = flat.stream().mapToInt(MapNode::y).min().orElse(0), maxY = flat.stream().mapToInt(MapNode::y).max().orElse(0);
        float scale = Math.min(256f / Math.max(1, maxX - minX), 84f / Math.max(1, maxY - minY));
        return mappedNodes = flat.stream().map(node -> new MapNode(155 + Math.round((node.x - (minX + maxX) / 2f) * scale),
                111 + Math.round((node.y - (minY + maxY) / 2f) * scale))).toList();
    }

    private int mapRoomAt(double mouseX, double mouseY) {
        var nodes = mapNodes();
        double best = 100;
        int nearest = -1;
        for (int i = 0; i < nodes.size(); i++) {
            var node = nodes.get(i);
            double dx = mouseX - left - node.x, dy = mouseY - top - node.y;
            double distance = dx * dx + dy * dy;
            if (distance < best) {
                best = distance;
                nearest = i;
            }
        }
        return nearest;
    }

    private void drawMap(GuiGraphics graphics, int mouseX, int mouseY) {
        var rooms = data.getList("Rooms", Tag.TAG_COMPOUND);
        var nodes = mapNodes();
        label(graphics, "Escolha uma sala", 12, 43, TEXT);
        String count = nodes.size() + " concluídas";
        label(graphics, count, 298 - font.width(count), 43, SUBTLE);
        OrbitUi.well(graphics, left + 12, top + 56, 286, 105, true);
        label(graphics, "N ↑", 19, 62, SUBTLE);
        int hover = mapRoomAt(mouseX, mouseY);
        var links = data.getIntArray("RoomLinks");
        for (int i = 1; i < links.length; i += 2) {
            var start = nodes.get(links[i - 1]);
            var end = nodes.get(links[i]);
            int steps = Math.max(1, Math.max(Math.abs(end.x - start.x), Math.abs(end.y - start.y)));
            for (int step = 0; step <= steps; step++) {
                int x = left + start.x + (end.x - start.x) * step / steps;
                int y = top + start.y + (end.y - start.y) * step / steps;
                graphics.fill(x, y, x + 2, y + 2, 0xFF48616B);
            }
        }
        for (int i = 0; i < nodes.size(); i++) {
            var node = nodes.get(i);
            int x = left + node.x, y = top + node.y;
            int color = OrbitUi.opaque(OrbAltarBlockEntity.ROOM_COLORS[rooms.getCompound(i).getInt("Color")]);
            if (i == room) {
                int size = 14 + (int) (2 + 2 * Math.sin(Util.getMillis() / 230d));
                graphics.renderOutline(x - size / 2, y - size / 2, size + 1, size + 1, 0xA0000000 | (color & 0xFFFFFF));
            }
            if (i == hover) graphics.renderOutline(x - 7, y - 7, 15, 15, 0xFFEDF3F8);
            graphics.fill(x - 4, y - 5, x + 5, y + 6, color);
            graphics.fill(x - 5, y - 4, x + 6, y + 5, color);
            if (data.contains("CurrentRoom") && i == data.getInt("CurrentRoom")) graphics.fill(x - 2, y - 2, x + 3, y + 3, OrbitUi.WELL);
        }
        int[] costs = data.getIntArray("TravelCosts");
        String cost = data.contains("CurrentRoom") && room == data.getInt("CurrentRoom") ? "Você está aqui"
                : room < costs.length ? costs[room] + " FE" : "";
        label(graphics, font.plainSubstrByWidth(rooms.getCompound(room).getString("Name"), 145), 62, 170, TEXT);
        label(graphics, cost, 298 - font.width(cost), 170, SUBTLE);
        if (hover >= 0) graphics.renderTooltip(font, Component.literal(rooms.getCompound(hover).getString("Name")), mouseX, mouseY);
    }

    private void materialEditor() {
        reviewSwap = null;
        if (!renovateMaterials) {
            for (var group : MaterialGroup.values()) {
                button(12 + 83 * group.ordinal(), 35, 80, 17,
                        (group == materialGroup ? "• " : "") + group.label, () -> {
                            if (group == materialGroup) return;
                            materialGroup = group;
                            selectMaterialPart(group.parts.getFirst());
                        });
            }
            button(editorWidth - 94, 13, 82, 16, "Paleta padrão", () -> changePalette(DomePalette.standard().write()))
                    .setTooltip(Tooltip.create(Component.literal("Volta às pedras vanilla e às luminárias do Orbital.")));
        }
        var parts = renovateMaterials ? List.of(DomePalette.Part.ROOF, DomePalette.Part.FLOOR, DomePalette.Part.WALL)
                : materialGroup.parts;
        for (int i = 0; i < parts.size(); i++) {
            var part = parts.get(i);
            String name = renovateMaterials && part == DomePalette.Part.ROOF ? "Teto" : part.label;
            materialRow(12, 75 + 17 * i, 80, 16, (part == materialPart ? "• " : "") + name,
                    () -> selectMaterialPart(part)).setTooltip(Tooltip.create(Component.literal(part.description)));
        }

        var search = new EditBox(font, left + resultsX, top + 75, resultsWidth, 17, Component.literal("Buscar blocos"));
        search.setMaxLength(80);
        search.setHint(Component.literal("Buscar nome ou mod"));
        search.setValue(materialQuery);
        addRenderableWidget(search);
        materialRows.clear();
        int rows = Math.max(1, (editorHeight - 135) / MATERIAL_ROW);
        for (int row = 0; row < rows; row++) {
            int index = row;
            materialRows.add(materialRow(resultsX + 22, 97 + row * MATERIAL_ROW, resultsWidth - 26, 17, "", () -> {
                var choice = filteredMaterials.get(materialOffset + index);
                materials.putString(materialPart.name(), choice.id());
                scheduleBrowse();
                refreshMaterialRows();
            }));
        }
        search.setResponder(query -> {
            materialQuery = query;
            materialOffset = 0;
            filterMaterials();
        });
        filterMaterials();
        if (renovateMaterials) {
            button(editorWidth - 192, editorHeight - 31, 76, 19, "Voltar", () -> leaveMaterials(false));
            reviewSwap = primary(editorWidth - 110, editorHeight - 31, 98, 19, "Revisar troca", this::reviewRenovation);
            reviewSwap.active = materials.contains(materialPart.name());
            return;
        }
        int actionY = editorHeight - 63;
        int actionWidth = (viewportWidth - 8) / 3;
        button(viewportX, actionY, actionWidth, 15, "Girar", () -> {
            catalogRotation = (catalogRotation + 1) % 4;
            rebuildCatalogModel();
        });
        button(viewportX + actionWidth + 4, actionY, actionWidth, 15, materialCutaway ? "Inteiro" : "Corte", () -> {
            materialCutaway = !materialCutaway;
            rebuildCatalogModel();
            rebuildWidgets();
        });
        button(viewportX + 2 * (actionWidth + 4), actionY, actionWidth, 15, "Restaurar", () -> {
            String standard = DomePalette.standard().write().getString(materialPart.name());
            if (standard.isEmpty()) materials.remove(materialPart.name());
            else materials.putString(materialPart.name(), standard);
            scheduleBrowse();
            refreshMaterialRows();
        }).setTooltip(Tooltip.create(Component.literal("Restaura o material padrão desta área.")));
        button(editorWidth - 192, editorHeight - 31, 76, 19, "Cancelar", () -> leaveMaterials(false));
        primary(editorWidth - 110, editorHeight - 31, 98, 19, "Aplicar paleta", () -> leaveMaterials(true));
    }

    private void reviewRenovation() {
        var choice = new CompoundTag();
        choice.putString(materialPart.name(), materials.getString(materialPart.name()));
        PacketDistributor.sendToServer(new PlannerNetwork.Request(altar, PlannerNetwork.RENOVATE, "", room,
                materialPart.ordinal(), false, false, choice));
        leaveMaterials(false);
    }

    private List<MaterialChoice> candidates(DomePalette.Part part) {
        return availableMaterials.computeIfAbsent(part, key -> BuiltInRegistries.BLOCK.stream()
                    .filter(block -> DomePalette.isSelectable(key, block))
                    .map(block -> {
                        String label = block.getName().getString();
                        String id = BuiltInRegistries.BLOCK.getKey(block).toString();
                        return new MaterialChoice(block, label, id, normalized(label + " " + id));
                    })
                    .sorted(Comparator.comparing((MaterialChoice choice) ->
                                    !DomePalette.isOrbitalLight(BuiltInRegistries.BLOCK.getKey(choice.block())))
                            .thenComparing(MaterialChoice::label, String.CASE_INSENSITIVE_ORDER))
                    .toList());
    }

    private void selectMaterialPart(DomePalette.Part part) {
        if (part == materialPart) return;
        materialPart = part;
        materialQuery = "";
        materialOffset = 0;
        materialCutaway = part == DomePalette.Part.FLOOR || part == DomePalette.Part.STAIRS
                || part == DomePalette.Part.PLANTER || part == DomePalette.Part.LIGHT;
        rebuildCatalogModel();
        rebuildWidgets();
    }

    private void changePalette(CompoundTag palette) {
        for (var part : DomePalette.Part.values()) materials.remove(part.name());
        materials.merge(palette);
        scheduleBrowse();
        refreshMaterialRows();
    }

    private void leaveMaterials(boolean apply) {
        showMaterials = false;
        renovateMaterials = false;
        reviewSwap = null;
        if (!apply && materialsBeforeEditor != null) {
            changePalette(materialsBeforeEditor);
        } else {
            rebuildCatalogModel();
        }
        materialsBeforeEditor = null;
        rebuildWidgets();
    }

    @Override
    public void onClose() {
        if (showMaterials && !showFleet) leaveMaterials(false);
        else super.onClose();
    }

    private void filterMaterials() {
        String query = normalized(materialQuery);
        filteredMaterials = candidates(materialPart).stream()
                .filter(choice -> choice.search().contains(query))
                .toList();
        materialOffset = Math.clamp(materialOffset, 0, Math.max(0, filteredMaterials.size() - materialRows.size()));
        refreshMaterialRows();
    }

    private void refreshMaterialRows() {
        String selected = materials.getString(materialPart.name());
        if (reviewSwap != null) reviewSwap.active = !selected.isEmpty();
        for (int row = 0; row < materialRows.size(); row++) {
            var button = materialRows.get(row);
            int index = materialOffset + row;
            button.visible = index < filteredMaterials.size();
            button.active = button.visible;
            if (!button.visible) continue;
            var choice = filteredMaterials.get(index);
            button.setMessage(Component.literal((choice.id().equals(selected) ? "• " : "") + choice.label()));
            String description = choice.label() + "\n" + choice.id();
            if (materialPart == DomePalette.Part.LIGHT || materialPart == DomePalette.Part.SIGNAL) {
                int light = choice.block().defaultBlockState().getLightEmission(
                        net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
                description += "\nLuz " + light + "/15";
            }
            button.setTooltip(Tooltip.create(Component.literal(description)));
        }
    }

    private static String normalized(String value) {
        return Normalizer.normalize(value.toLowerCase(Locale.ROOT).strip(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
    }

    private static int compatiblePorts(int source, int target) {
        int result = 0;
        for (var direction : PORTS) {
            if ((source & 1 << direction.ordinal()) != 0 && (target & 1 << direction.getOpposite().ordinal()) != 0) {
                result |= 1 << direction.ordinal();
            }
        }
        return result;
    }

    private Button button(int x, int y, int w, String label, Runnable run) {
        return button(x, y, w, 20, label, run);
    }

    private Button button(int x, int y, int width, int height, String label, Runnable run) {
        return control(x, y, width, height, label, OrbitUi.Role.CONTROL, null, false, run);
    }

    private Button primary(int x, int y, int width, int height, String label, Runnable run) {
        return control(x, y, width, height, label, OrbitUi.Role.PRIMARY, null, false, run);
    }

    private Button materialRow(int x, int y, int width, int height, String label, Runnable run) {
        return control(x, y, width, height, label, OrbitUi.Role.ROW, null, false, run);
    }

    private Button control(int x, int y, int width, int height, String label, OrbitUi.Role role, OrbitUi.Icon icon,
                           boolean selected, Runnable run) {
        return addRenderableWidget(new OrbitUi.Control(font, left + x, top + y, width, height, label,
                style().accent, role, icon, selected, run));
    }

    private void request(int action, String blueprint) {
        PacketDistributor.sendToServer(new PlannerNetwork.Request(altar, action, blueprint, room, face.ordinal(),
                clearObstructions, connectGallery, materials.copy(), new CorridorPlan.Options(corridor, grade, priority, pillars)));
    }

    private void requestDrone(int action, int upgrade) {
        PacketDistributor.sendToServer(new PlannerNetwork.Request(altar, action, "", selectedDrone, upgrade,
                false, false, new CompoundTag()));
    }

    private void focusDrone(int slot) {
        showFleet = true;
        selectedDrone = Math.clamp(slot, 0, Math.max(0, data.getInt("Drones") - 1));
        var profile = data.getList("DroneJobs", Tag.TAG_COMPOUND).getCompound(selectedDrone);
        displayedCharge = (float) profile.getInt("Energy") / Math.max(1, profile.getInt("Capacity"));
    }

    private void setNotice(String message) {
        if (message.isEmpty()) return;
        notice = message;
        noticeUntil = Util.getMillis() + 4500;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The planner draws its own backdrop before the model and widgets.
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0x97091017);
        if (showMaterials && !showFleet && data.getString("State").equals("IDLE")) {
            drawMaterialEditor(graphics);
            super.render(graphics, mouseX, mouseY, partialTick);
            return;
        }
        var style = style();
        drawPanel(graphics, PANEL_WIDTH, PANEL_HEIGHT, 37);
        var item = OrbitalArchitect.ALTARS.get(Math.clamp(data.getInt("Tier") - 1, 0, 3));
        drawSmallItem(graphics, new ItemStack(item.get()), 12, 5);
        graphics.drawString(font, "Orbit", left + 30, top + 7, TEXT, false);
        label(graphics, "T" + data.getInt("Tier"), 61, 7, style.accent);
        drawPower(graphics, mouseX, mouseY);
        long elapsed = Util.getMillis() - viewChangedAt;
        if (elapsed < 260) {
            int glow = (int) (82 * (1 - elapsed / 260f));
            graphics.fill(left + 1, top + 38, left + PANEL_WIDTH - 1, top + 39, glow << 24 | style.accent);
        }
        int[] roster = data.getIntArray("DroneRoster");
        if (showFleet) drawFleet(graphics, roster);
        else if (showMap) drawMap(graphics, mouseX, mouseY);
        else switch (data.getString("State")) {
            case "IDLE" -> {
                if (showOperations) {
                    label(graphics, "Conexão e ordem de montagem", 12, 43, TEXT);
                    int rooms = data.getList("Rooms", Tag.TAG_COMPOUND).size();
                    if (rooms > 0) {
                        label(graphics, "Sala concluída selecionada", 12, 130, TEXT);
                        centered(graphics, (room + 1) + "/" + rooms, 265, 128, SUBTLE);
                    }
                } else catalogLabels(graphics);
            }
            case "PREVIEW", "INSPECT" -> {
                boolean valid = data.getString("State").equals("PREVIEW");
                if (!showCosts) {
                    drawPreview(graphics, model, 12, 43, valid ? 150 : 286, 87, viewChangedAt, false);
                    String legend = data.contains("Renovation") ? "Blocos a trocar · " + data.getString("Renovation")
                            : data.contains("Blocked") ? "Vermelho = bloqueio" : hasGalleryLink()
                            ? "Roxo = galeria" : "Amarelo = encaixe";
                    label(graphics, legend,
                            12, 133, 0x9FBFCB);
                }
                if (data.contains("Renovation")) {
                    label(graphics, data.getInt("Total") + " trocas · " + data.getInt("SiteProtectedCount") + " blocos preservados",
                            12, 158, SUBTLE);
                } else if (showSurvey) {
                    String survey = data.getBoolean("Repair") ? "Repor " + data.getInt("Total") + " · verde = bloco ausente"
                            : "Escavar " + data.getInt("SiteCutCount") + " · Proteger " + data.getInt("SiteProtectedCount")
                                    + " · Apoios " + data.getLongArray("SiteSupports").length;
                    label(graphics, survey, 12, 158, 0xFFD38C);
                } else if (data.getInt("Grade") != 0) {
                    label(graphics, "Degraus no corredor · desnível " + data.getInt("Grade"), 12, 158, SUBTLE);
                }
                if (valid) {
                    costs(graphics, showCosts ? 12 : 168, showCosts ? 286 : 130);
                    drawEnergyEstimate(graphics, showCosts ? 183 : 168);
                    if (data.getInt("Obstructions") == 0 && !hasGalleryLink()) drawReadiness(graphics);
                }
            }
            default -> {
                String phase = switch (data.getString("State")) {
                    case "EXCAVATING" -> "Preparação do terreno e montagem";
                    case "UPGRADING" -> data.contains("Renovation") ? "Trocando blocos · " + data.getString("Renovation")
                            : "Melhorando a estrutura";
                    default -> "Montando a estrutura";
                };
                if (data.getBoolean("CanSkipGallery")) phase = "Ligando as galerias";
                int current = data.getInt("Cursor"), total = data.getInt("Total");
                label(graphics, phase, 12, 51, 0xB6FFFA);
                label(graphics, current + " / " + total + " posições", 12, 71, 0xE8F1F5);
                displayedProgress += ((total > 0 ? (float) current / total : 0) - displayedProgress) * .13f;
                meter(graphics, 12, 86, 286, displayedProgress, 0xFF62D9BD, true);
                drawWaiting(graphics, 102);
                label(graphics, "Drones  " + data.getInt("Consumption") + " FE/s  ·  "
                        + data.getString("WorkPriority"), 12, 155, SUBTLE);
                if (data.contains("Blocked")) {
                    label(graphics, "Posição · " + BlockPos.of(data.getLong("Blocked")).toShortString(),
                            12, 169, 0xFF807A);
                }
            }
        }
        String status = showFleet && Util.getMillis() < noticeUntil ? notice : data.getString("Message");
        boolean hasNotice = !status.isEmpty();
        if (!hasNotice) status = screenHint();
        var messages = font.split(Component.literal(status), PANEL_WIDTH - 24);
        for (int i = 0; i < Math.min(messages.size(), 2); i++) {
            graphics.drawString(font, messages.get(i), left + 12, top + 214 + 9 * i, hasNotice ? 0xFFD38C : SUBTLE, false);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private String screenHint() {
        if (showMap) return "Selecione um ponto para ver o destino e o custo.";
        if (showFleet) return "Selecione um drone para ver a bateria e a tarefa atual.";
        if (showOperations) return "Essas escolhas valem para a próxima revisão.";
        return switch (data.getString("State")) {
            case "IDLE" -> data.getInt("CompletedRooms") == 0 ? "Escolha a primeira planta para iniciar a base."
                    : "Os pontos verdes da prévia são encaixes disponíveis.";
            case "PREVIEW" -> "Confira os materiais e a energia antes de confirmar.";
            case "INSPECT" -> "Use a vistoria para localizar o ponto que precisa de atenção.";
            default -> "Abra Drones para acompanhar a frota em tempo real.";
        };
    }

    private void drawPanel(GuiGraphics graphics, int panelWidth, int panelHeight, int headerHeight) {
        OrbitUi.panel(graphics, left, top, panelWidth, panelHeight, headerHeight, style().accent);
    }

    private void drawPower(GuiGraphics graphics, int mouseX, int mouseY) {
        int stored = data.getInt("Energy");
        int capacity = data.getInt("EnergyCapacity");
        int input = data.getInt("InputConsumption"), charging = data.getInt("ChargingConsumption");
        int output = data.getInt("OutputConsumption") + data.getInt("TravelConsumption");
        float fraction = (float) stored / Math.max(1, capacity);
        displayedCore = displayedCore < 0 ? fraction : displayedCore + (fraction - displayedCore) * .18f;
        label(graphics, stored + "/" + capacity + " FE", 93, 4, TEXT);
        meter(graphics, 93, 14, 85, displayedCore, 0x69CDBB, input > 0);
        label(graphics, "+" + input + " / −" + (charging + output), 187, 7, SUBTLE);
        if (mouseX >= left + 90 && mouseX < left + 232 && mouseY >= top + 3 && mouseY < top + 21) {
            graphics.renderComponentTooltip(font, List.of(Component.literal("Energia do núcleo · FE/s"),
                    Component.literal("Entrada " + input + " · Recarga " + charging),
                    Component.literal("Saída e teleporte " + output + " · Reserva " + data.getInt("EnergyReserve") + " FE")),
                    mouseX, mouseY);
        }
    }

    private void fleetControls() {
        int count = data.getInt("Drones");
        selectedDrone = Math.clamp(selectedDrone, 0, Math.max(0, count - 1));
        for (int slot = 0; slot < count; slot++) {
            int index = slot;
            button(fleetSlotX(slot), 91, 54, 14, (slot == selectedDrone ? "• " : "") + "Drone " + (slot + 1),
                    () -> { focusDrone(index); rebuildWidgets(); });
        }
        var profile = data.getList("DroneJobs", Tag.TAG_COMPOUND).getCompound(selectedDrone);
        var installed = profile.getIntArray("Upgrades");
        int[] roster = data.getIntArray("DroneRoster");
        int tier = selectedDrone < roster.length ? roster[selectedDrone] : 1;
        boolean idle = data.getString("State").equals("IDLE");
        if (idle && tier >= 2) {
            for (var upgrade : DroneUpgrade.values()) {
                boolean applied = Arrays.stream(installed).anyMatch(index -> index == upgrade.ordinal());
                var control = button(12 + 73 * upgrade.ordinal(), 179, 67, 12, (applied ? "• " : "") + upgrade.label,
                        () -> requestDrone(PlannerNetwork.DRONE_UPGRADE, upgrade.ordinal()));
                control.active = !applied && installed.length < tier - 1;
                control.setTooltip(Tooltip.create(Component.literal(upgrade.description
                        + (applied ? "\nInstalado neste drone." : "\nUse o módulo do inventário."))));
            }
        }
        if (idle && selectedDrone > 0) {
            button(173, 192, 125, "Recolher este drone", () -> requestDrone(PlannerNetwork.RECALL_DRONE, 0));
        } else if (data.contains("Blocked")) {
            button(173, 192, 125, "Ver bloqueio no mundo", this::onClose);
        }
        button(12, 192, 84, "Fechar", this::onClose);
    }

    private void drawFleet(GuiGraphics graphics, int[] roster) {
        boolean working = isWorking();
        boolean creative = data.getBoolean(working ? "FreeOperation" : "Creative");
        String status = working ? (data.getString("Waiting").isEmpty() ? "Em operação" : "Em espera")
                : "Docas prontas  " + data.getInt("DockReady") + "/" + data.getInt("Docks");
        label(graphics, "Frota de construção", 12, 43, TEXT);
        label(graphics, status, 298 - font.width(status), 43, SUBTLE);
        int slots = data.getInt("DroneCapacity");
        var tasks = data.getList("DroneJobs", Tag.TAG_COMPOUND);
        for (int slot = 0; slot < slots; slot++) {
            int x = fleetSlotX(slot);
            int border = slot == selectedDrone ? 0xFF000000 | style().accent : DIVIDER;
            graphics.fill(left + x, top + 55, left + x + 54, top + 106, 0xFF111921);
            graphics.renderOutline(left + x, top + 55, 54, 51, border);
            if (slot >= roster.length) {
                centered(graphics, "Livre", x + 27, 77, SUBTLE);
                continue;
            }
            var tier = DroneTier.at(roster[slot]);
            float lift = slot == selectedDrone ? 1.2f * (float) Math.sin(Util.getMillis() / 400.0) : 0;
            drawDrone(graphics, tier.level, x + 10, 55 + lift, 1.5f);
            label(graphics, "T" + tier.level, x + 34, 65, tier.rgb);
            String phase = tasks.getCompound(slot).getString("Phase");
            if (!phase.isEmpty()) centered(graphics, font.plainSubstrByWidth(phase, 49), x + 27, 81, SUBTLE);
        }
        if (selectedDrone >= roster.length) return;
        var task = tasks.getCompound(selectedDrone);
        int charge = task.getInt("Energy");
        int capacity = task.getInt("Capacity");
        int tier = roster[selectedDrone];
        String energy = charge + " / " + capacity + " FE";
        label(graphics, "Bateria do drone " + (selectedDrone + 1), 12, 113, TEXT);
        label(graphics, energy, 298 - font.width(energy), 113, TEXT);
        displayedCharge += ((capacity > 0 ? (float) charge / capacity : 0) - displayedCharge) * .14f;
        meter(graphics, 12, 125, 286, displayedCharge, 0xFF000000 | DroneTier.at(tier).rgb, working);
        String cost = creative ? "Criativo · 0 FE" : "Montar " + task.getInt("Cost") + " · Limpar " + task.getInt("DigCost") + " FE";
        int batch = task.getInt(creative ? "CreativeBatch" : "Batch");
        int digBatch = task.getInt(creative ? "CreativeDigBatch" : "DigBatch");
        label(graphics, cost, 12, 135, SUBTLE);
        String usage = task.getInt("Consumption") + " FE/s agora";
        label(graphics, usage, 298 - font.width(usage), 135, TEXT);
        label(graphics, batch + " montar  /  " + digBatch + " escavar por lote  ·  Tiro "
                + task.getInt("Speed") + " bl/s", 12, 146, SUBTLE);
        if (!working) {
            String upgrades = tier < 2 ? "Módulos disponíveis a partir do T2"
                    : "Módulos  " + task.getIntArray("Upgrades").length + "/" + (tier - 1);
            label(graphics, upgrades, 12, 159, TEXT);
            label(graphics, selectedDrone == 0 ? "Integrado ao núcleo"
                    : task.getString("Phase").equals("Sem doca") ? "Precisa de uma doca" : "Drone adicional", 183, 159, SUBTLE);
            if (tier >= 2) for (var upgrade : DroneUpgrade.values()) {
                drawSmallItem(graphics, upgrade.stack(), 39 + 73 * upgrade.ordinal(), 167);
            }
        } else if (!task.contains("Target") && !data.getString("Waiting").isEmpty()) {
            drawWaiting(graphics, 159);
        } else {
            String phase = task.getString("Phase");
            label(graphics, phase.isEmpty() ? "Aguardando a próxima tarefa" : phase, 12, 159, TEXT);
            var cargo = ResourceLocation.tryParse(task.getString("Cargo"));
            if (cargo != null) {
                var item = BuiltInRegistries.ITEM.get(cargo);
                String load = "Carga · " + task.getInt("Remaining") + " × " + item.getDefaultInstance().getHoverName().getString();
                if (task.contains("StockPos")) load += " · baú " + BlockPos.of(task.getLong("StockPos")).toShortString();
                label(graphics, font.plainSubstrByWidth(load, 286),
                        12, 170, SUBTLE);
            }
            if (task.contains("Target")) {
                label(graphics, "Destino · " + BlockPos.of(task.getLong("Target")).toShortString(), 12, 181, SUBTLE);
            }
        }
    }

    private void drawWaiting(GuiGraphics graphics, int y) {
        var lines = font.split(Component.literal(data.getString("Waiting")), 286);
        for (int i = 0; i < Math.min(lines.size(), 2); i++) {
            graphics.drawString(font, lines.get(i), left + 12, top + y + 10 * i, 0xFFD38C);
        }
    }

    private int fleetSlotX(int slot) {
        int slots = data.getInt("DroneCapacity");
        return (PANEL_WIDTH - (slots * 54 + (slots - 1) * 4)) / 2 + slot * 58;
    }

    private boolean isWorking() {
        return switch (data.getString("State")) {
            case "EXCAVATING", "BUILDING", "UPGRADING" -> true;
            default -> false;
        };
    }

    private boolean hasGalleryLink() { return data.getLongArray("GalleryLink").length == 2; }

    private void centered(GuiGraphics graphics, String text, int x, int y, int color) {
        graphics.drawCenteredString(font, text, left + x, top + y, color);
    }

    private void drawDrone(GuiGraphics graphics, int tier, int x, float y, float scale) {
        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(left + x, top + y, 0);
        pose.scale(scale, scale, 1);
        graphics.renderItem(droneIcons.get(DroneTier.at(tier).level - 1), 0, 0);
        pose.popPose();
    }

    private void drawSmallItem(GuiGraphics graphics, ItemStack stack, int x, int y) {
        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(left + x, top + y, 0);
        pose.scale(.75f, .75f, 1);
        graphics.renderItem(stack, 0, 0);
        pose.popPose();
    }

    private void catalogLabels(GuiGraphics graphics) {
        var rooms = data.getList("Rooms", Tag.TAG_COMPOUND);
        var plans = visiblePlans();
        var plan = plans.isEmpty() ? new CompoundTag() : plans.get(selected);
        label(graphics, "1 · Planta", 12, 44, TEXT);
        label(graphics, "2 · Encaixe", 151, 43, TEXT);
        String dimensions = plans.isEmpty() ? "" : plan.getString("Size") + "  ·  T" + plan.getInt("Tier");
        label(graphics, dimensions, 298 - font.width(dimensions), 43, SUBTLE);
        if (catalogModel != null) {
            drawPreview(graphics, catalogModel, 151, 54, 147, 70, catalogChangedAt, awaitingBrowse || browsePending);
        } else {
            OrbitUi.well(graphics, left + 151, top + 54, 147, 70, true);
            loadingPreview(graphics, 151, 54, 147, 70, awaitingBrowse || browsePending);
        }
        int pages = Math.max(1, (plans.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (pages > 1) centered(graphics, (page + 1) + " / " + pages, 77, 166, SUBTLE);
        if (rooms.isEmpty()) {
            label(graphics, "Núcleo inicial", 151, 150, 0xE8F1F5);
            label(graphics, "Galeria para cabos sob o piso", 151, 166, 0x9FBFCB);
        } else {
            var name = rooms.getCompound(room).getString("Name");
            int color = OrbAltarBlockEntity.ROOM_COLORS[rooms.getCompound(room).getInt("Color")];
            label(graphics, font.plainSubstrByWidth((room + 1) + " · " + name, 100), 174, 150, color);
        }
    }

    private void drawMaterialEditor(GuiGraphics graphics) {
        drawPanel(graphics, editorWidth, editorHeight, 58);
        label(graphics, renovateMaterials ? "Trocar blocos" : "Materiais do projeto", 12, 11, TEXT);
        var rooms = data.getList("Rooms", Tag.TAG_COMPOUND);
        label(graphics, renovateMaterials ? rooms.getCompound(room).getString("Name") : "Escolha os blocos por área", 12, 23, SUBTLE);
        label(graphics, renovateMaterials ? "1 · Área da sala" : "Área da planta", 12, 64, SUBTLE);
        label(graphics, "Blocos disponíveis", resultsX, 64, SUBTLE);
        label(graphics, renovateMaterials ? "2 · Material novo" : "Prévia 3D", viewportX, 64, TEXT);
        graphics.fill(left + 96, top + 64, left + 97, top + editorHeight - 40, DIVIDER);
        graphics.fill(left + resultsX, top + 95, left + resultsX + resultsWidth,
                top + editorHeight - 39, 0xFF111921);
        int previewHeight = editorHeight - 143;
        if (renovateMaterials) {
            var id = ResourceLocation.tryParse(materials.getString(materialPart.name()));
            var y = 87;
            if (id == null) {
                label(graphics, "Escolha um bloco da lista.", viewportX + 8, y, SUBTLE);
            } else {
                var item = new ItemStack(BuiltInRegistries.BLOCK.get(id));
                graphics.renderItem(item, left + viewportX + 8, top + y);
                label(graphics, font.plainSubstrByWidth(item.getHoverName().getString(), viewportWidth - 37), viewportX + 30, y + 4, TEXT);
            }
            for (var line : font.split(Component.literal("Na revisão você confere a área em 3D, o estoque e o custo em FE. Os drones retiram o bloco antigo quando já têm o substituto."), viewportWidth - 18)) {
                graphics.drawString(font, line, left + viewportX + 8, top + y + 27, SUBTLE, false);
                y += 11;
            }
        } else if (catalogModel == null) {
            graphics.fill(left + viewportX, top + 75, left + viewportX + viewportWidth,
                    top + 75 + previewHeight, 0xFF111921);
            centered(graphics, awaitingBrowse || browsePending ? "Carregando modelo…" : "Prévia indisponível",
                    viewportX + viewportWidth / 2, 71 + previewHeight / 2, SUBTLE);
        } else {
            drawPreview(graphics, catalogModel, viewportX, 75, viewportWidth, previewHeight,
                    catalogChangedAt, awaitingBrowse || browsePending);
        }
        int count = Math.min(materialRows.size(), filteredMaterials.size() - materialOffset);
        for (int i = 0; i < count; i++) {
            graphics.renderItem(new ItemStack(filteredMaterials.get(materialOffset + i).block()),
                    left + resultsX + 3, top + 97 + i * MATERIAL_ROW);
        }
        if (count == 0) label(graphics, "Sem resultados", resultsX + 5, 101, SUBTLE);
        int scrollRange = filteredMaterials.size() - count;
        if (scrollRange > 0) {
            int x = left + resultsX + resultsWidth - 3;
            int track = editorHeight - 138;
            int thumb = Math.max(8, track * count / filteredMaterials.size());
            int y = top + 97 + (track - thumb) * materialOffset / scrollRange;
            graphics.fill(x, top + 97, x + 2, top + 97 + track, DIVIDER);
            graphics.fill(x, y, x + 2, y + thumb, 0xFF607180);
        }
        var id = ResourceLocation.tryParse(materials.getString(materialPart.name()));
        String current = id != null ? BuiltInRegistries.BLOCK.get(id).getName().getString()
                : materialPart == DomePalette.Part.DOOR ? "Aço da eclusa" : "Luminária da planta";
        if (!renovateMaterials) label(graphics, font.plainSubstrByWidth("Em uso  ·  " + current, viewportWidth),
                viewportX, editorHeight - 46, TEXT);
        graphics.fill(left + 12, top + editorHeight - 37, left + editorWidth - 12, top + editorHeight - 36, DIVIDER);
        label(graphics, font.plainSubstrByWidth(renovateMaterials ? "3 · Revisar a troca" : "Paleta para a próxima obra", editorWidth - 208),
                12, editorHeight - 25, SUBTLE);
    }

    private void drawReadiness(GuiGraphics graphics) {
        if (data.getInt("Total") == 0 && data.getInt("HubRetrofitTier") > 0) {
            label(graphics, "Reforma pronta · sem troca de blocos", 12, 174, 0x88E9C1);
            return;
        }
        if (data.getBoolean("Creative")) {
            label(graphics, "Criativo · sem consumo de itens ou energia", 12, 174, 0x88E9C1);
            return;
        }
        if (Math.max(0, data.getInt("Energy") - data.getInt("EnergyReserve")) + data.getInt("FleetEnergy") < data.getInt("MinWorkEnergy")) {
            label(graphics, "Conecte FE ao altar para os drones trabalharem.", 12, 174, 0xFFBA82);
            return;
        }
        boolean missing = data.getList("Costs", Tag.TAG_COMPOUND).stream().map(CompoundTag.class::cast)
                .anyMatch(cost -> cost.getInt("Stock") < cost.getInt("Count"));
        label(graphics, missing ? "Faltam materiais. A obra pausa se o estoque acabar."
                : "Materiais suficientes para este projeto.", 12, 174, missing ? 0xFFBA82 : 0x88E9C1);
    }

    private void drawEnergyEstimate(GuiGraphics graphics, int x) {
        int gallery = connectGallery ? OrbAltarBlockEntity.ENERGY_PER_BLOCK * 2 : 0;
        int low = data.getInt("EnergyLow") + gallery;
        int high = data.getInt("EnergyHigh") + gallery;
        String estimate = low == high ? "≈ " + compactEnergy(low) + " FE"
                : "≈ " + compactEnergy(low) + "–" + compactEnergy(high) + " FE";
        label(graphics, data.getBoolean("Creative") ? "Sobrev. " + estimate : estimate, x, 135, 0x77E2D5);
    }

    private static String compactEnergy(int value) {
        return value < 1000 ? Integer.toString(value) : String.format(Locale.ROOT, "%.1fk", value / 1000d);
    }

    private void drawPreview(GuiGraphics graphics, PlannerModel preview, int x, int y, int w, int h, long changedAt, boolean pending) {
        boolean ready = preview.render(graphics, left + x, top + y, w, h);
        int color = style().accent;
        int phase = (int) Math.clamp(Util.getMillis() - Math.max(changedAt, preview.uploadedAt()), 0, 220);
        if (phase < 220 && ready && !pending) {
            int opacity = 72 * (220 - phase) / 220;
            int row = top + y + 1 + (h - 3) * phase / 220;
            graphics.fill(left + x + 1, top + y + 1, left + x + w - 1, top + y + h - 1, opacity << 24 | 0x101C26);
            graphics.fill(left + x + 1, row, left + x + w - 1, row + 1, opacity << 24 | color);
        }
        graphics.fill(left + x, top + y, left + x + 11, top + y + 1, 0xFF000000 | color);
        graphics.fill(left + x, top + y, left + x + 1, top + y + 7, 0xFF000000 | color);
        if (pending || !ready) loadingPreview(graphics, x, y, w, h, true);
    }

    private void loadingPreview(GuiGraphics graphics, int x, int y, int w, int h, boolean pending) {
        String text = pending ? "Atualizando 3D" + ".".repeat(1 + (int) (Util.getMillis() / 240 % 3)) : "Prévia indisponível";
        int box = font.width(text) + 12;
        int center = x + w / 2;
        graphics.fill(left + center - box / 2, top + y + h - 17, left + center + box / 2, top + y + h - 3, 0xDF101A24);
        centered(graphics, text, center, y + h - 14, SUBTLE);
    }

    private void meter(GuiGraphics graphics, int x, int y, int w, float ratio, int color, boolean moving) {
        OrbitUi.meter(graphics, left + x, top + y, w, ratio, color, moving);
    }

    private List<CompoundTag> visiblePlans() {
        return data.getList("Catalog", Tag.TAG_COMPOUND).stream().map(CompoundTag.class::cast)
                .filter(plan -> filter == 0 || (filter == 2) == plan.getString("Id").endsWith("_xl"))
                .toList();
    }

    private void costs(GuiGraphics graphics, int x, int width) {
        var costs = data.getList("Costs", Tag.TAG_COMPOUND);
        boolean creative = data.getBoolean("Creative");
        label(graphics, creative ? "Criativo · 0 FE" : "Materiais", x, 48, 0x70E6D8);
        int max = Math.max(0, costs.size() - 5);
        stockOffset = Math.clamp(stockOffset, 0, max);
        for (int i = stockOffset; i < Math.min(costs.size(), stockOffset + 5); i++) {
            var cost = costs.getCompound(i);
            var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(cost.getString("Block")));
            int y = 62 + (i - stockOffset) * 13;
            int required = cost.getInt("Count");
            int stock = cost.getInt("Stock");
            String quantity = creative ? "×" + required : stock + "/" + required;
            int amountWidth = font.width(quantity);
            graphics.pose().pushPose();
            graphics.pose().translate(left + x, top + y - 2, 0);
            graphics.pose().scale(.64f, .64f, 1);
            graphics.renderItem(new ItemStack(block), 0, 0);
            graphics.pose().popPose();
            label(graphics, font.plainSubstrByWidth(block.getName().getString(), Math.max(10, width - 17 - amountWidth)),
                    x + 13, y, 0xE8F1F5);
            label(graphics, quantity, x + width - amountWidth, y, creative || stock >= required ? 0x88E9C1 : 0xFFBA82);
        }
        if (max > 0) label(graphics, "Role para ver mais", x, 123, 0x9FBFCB);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && showMap && !showMaterials) {
            int target = mapRoomAt(mouseX, mouseY);
            if (target >= 0) {
                room = target;
                scheduleBrowse();
                rebuildWidgets();
                return true;
            }
        }
        if (button == 0 && !showFleet && !showMaterials && !showOperations && catalogModel != null && !browsePending && !awaitingBrowse
                && data.getString("State").equals("IDLE")) {
            double x = mouseX - left - 151, y = mouseY - top - 54;
            if (x >= 0 && x < 147 && y >= 0 && y < 70) {
                var port = catalogModel.socketAt(x, y, 147, 70);
                if (port != null) {
                    face = port;
                    selectChanged();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (showMaterials && !showFleet) {
            if (mouseX >= left + resultsX && mouseX <= left + resultsX + resultsWidth
                    && mouseY >= top + 95 && mouseY < top + editorHeight - 39) {
                materialOffset = Math.clamp(materialOffset - (int) Math.signum(scrollY), 0,
                        Math.max(0, filteredMaterials.size() - materialRows.size()));
                refreshMaterialRows();
                return true;
            }
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        if (data.getString("State").equals("PREVIEW") && mouseX >= left + (showCosts ? 12 : 168)
                && mouseX <= left + 298 && mouseY >= top + 42 && mouseY <= top + 142) {
            int max = Math.max(0, data.getList("Costs", Tag.TAG_COMPOUND).size() - 5);
            stockOffset = Math.clamp(stockOffset - (int) Math.signum(scrollY), 0, max);
            return true;
        }
        if (data.getString("State").equals("IDLE") && mouseX >= left + 12 && mouseX <= left + 142
                && mouseY >= top + 74 && mouseY <= top + 160) {
            int last = Math.max(0, (visiblePlans().size() - 1) / PAGE_SIZE);
            int next = Math.clamp(page - (int) Math.signum(scrollY), 0, last);
            if (next != page) {
                page = next;
                selected = page * PAGE_SIZE;
                selectChanged();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void label(GuiGraphics graphics, String text, int x, int y, int color) {
        graphics.drawString(font, text, left + x, top + y, color);
    }

    private static String directionName(Direction direction) {
        return switch (direction) {
            case NORTH -> "Norte";
            case SOUTH -> "Sul";
            case EAST -> "Leste";
            case WEST -> "Oeste";
            case UP -> "Acima";
            case DOWN -> "Abaixo";
        };
    }
    private AltarTierStyle style() {
        return AltarTierStyle.from(data.getInt("Tier"));
    }

}











