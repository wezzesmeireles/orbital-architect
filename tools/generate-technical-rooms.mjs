import { existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { gzipSync } from 'node:zlib';

const data = new URL('../src/main/resources/data/orbital_architect/', import.meta.url);
for (const folder of ['structure/', 'structure/legacy/', 'orbital_rooms/', 'orbital_rooms_legacy/']) {
    mkdirSync(new URL(folder, data), { recursive: true });
}

function room(width, height, depth, floor, wall, trim, deck, roof) {
    const cx = (width - 1) / 2;
    const cz = (depth - 1) / 2;
    const ports = {
        north: [cx, floor + 1, 0], south: [cx, floor + 1, depth - 1],
        west: [0, floor + 1, cz], east: [width - 1, floor + 1, cz],
        down: [cx, 0, cz], up: [cx, height - 1, cz],
    };
    const blocks = new Map();
    const put = (x, y, z, block) => {
        const key = [x, y, z].join(',');
        if (block) blocks.set(key, { pos: [x, y, z], block });
        else blocks.delete(key);
    };
    const atEdge = (x, z) => x === 0 || z === 0 || x === width - 1 || z === depth - 1;
    for (let y = 0; y < height; y++) {
        for (let z = 0; z < depth; z++) {
            for (let x = 0; x < width; x++) {
                const edge = atEdge(x, z);
                if (edge) {
                    const rib = (x % 4 === 0 && (z === 0 || z === depth - 1))
                        || (z % 4 === 0 && (x === 0 || x === width - 1));
                    put(x, y, z, rib || y === floor || y === height - 1 ? trim : wall);
                } else if (y === 0 || y === floor) {
                    put(x, y, z, deck);
                } else if (y === height - 1) {
                    put(x, y, z, roof);
                }
            }
        }
    }

    const largePorts = new Set();
    function openings() {
        for (const [direction, [x, y, z]] of Object.entries(ports)) {
            if (direction === 'up' || direction === 'down') {
                put(x, y, z, null);
                continue;
            }
            const width = largePorts.has(direction) ? 4 : 2;
            const alongX = direction === 'north' || direction === 'south';
            for (let row = 0; row < width; row++) {
                for (let col = 0; col < width; col++) {
                    put(x + (alongX ? col : 0), y + row, z + (alongX ? 0 : col), null);
                }
            }
        }
    }

    return { size: [width, height, depth], ports, blocks, put, openings, largePorts };
}

const GALLERY_FLOOR = 4;
const horizontal = direction => direction !== 'up' && direction !== 'down';

function withGallery(original) {
    const [width, height, depth] = original.size;
    const oldFloor = original.ports.north[1] - 1;
    const plan = room(width, height - oldFloor + GALLERY_FLOOR, depth, GALLERY_FLOOR,
        'orbital_architect:ribbed_wall', 'orbital_architect:deck_plate',
        'orbital_architect:technical_floor', 'orbital_architect:technical_floor');
    for (const { pos: [x, y, z] } of plan.blocks.values()) {
        if (y >= GALLERY_FLOOR) plan.put(x, y, z, null);
    }
    for (const { pos: [x, y, z], block } of original.blocks.values()) {
        if (y >= oldFloor) plan.put(x, y + GALLERY_FLOOR - oldFloor, z, block);
    }
    for (const direction of Object.keys(plan.ports)) {
        if (!original.ports[direction]) delete plan.ports[direction];
    }
    for (const direction of original.largePorts) plan.largePorts.add(direction);

    // Access from the service aisle. The three blocks above the gallery slab stay clear.
    for (let step = 0; step < GALLERY_FLOOR; step++) {
        const y = GALLERY_FLOOR - step;
        const x = 2 + step;
        for (let head = y + 1; head <= y + 2; head++) plan.put(x, head, 2, null);
        plan.put(x, y, 2, 'orbital_architect:technical_stairs[facing=west]');
    }
    for (const [direction, [x, , z]] of Object.entries(plan.ports)) {
        if (horizontal(direction)) plan.put(x, 2, z, 'orbital_architect:hazard_plate');
    }
    return plan;
}

function roundPlan(source, id, tier, isHub) {
    const [oldWidth, oldHeight, oldDepth] = source.size;
    const floor = GALLERY_FLOOR;
    const rise = Math.max(5, Math.min(9, Math.round(Math.max(oldWidth, oldDepth) / 5)));
    const spring = Math.max(floor + 5, oldHeight - 3);
    const height = spring + rise + 1;
    const limit = Math.floor(Math.sqrt(110_000 / height));
    const minimumDiameter = id === 'capsule' ? 19 : 25;
    const diameter = Math.min(limit - (limit % 2 === 0 ? 1 : 0), Math.max(minimumDiameter, oldWidth + 6, oldDepth + 6));
    const center = (diameter - 1) / 2;
    const radius = center - 3;
    const blocks = new Map();
    const volume = new Map();
    const ports = {};
    const largePorts = new Set(Object.keys(source.ports).filter(horizontal));
    const stone = 'orbital_architect:ceramic_bulkhead';
    const deck = 'orbital_architect:technical_floor';
    const floorSurface = id === 'starter' ? 'oak_planks' : deck;
    const glass = 'orbital_architect:observation_glass';
    const cutStone = 'orbital_architect:ribbed_wall';
    const gold = 'orbital_architect:hull_panel';
    const lantern = 'orbital_architect:orbital_light';
    const plate = 'orbital_architect:hazard_plate';
    const trim = 'orbital_architect:hub_trim_' + tier;
    function put(x, y, z, block) {
        const pos = [x, y, z];
        const key = pos.join(',');
        if (block) blocks.set(key, { pos, block });
        else blocks.delete(key);
    }
    function contains(x, z, r) {
        return (x - center) ** 2 + (z - center) ** 2 <= (r + 0.35) ** 2;
    }
    function edge(x, z, r) {
        return !contains(x - 1, z, r) || !contains(x + 1, z, r)
            || !contains(x, z - 1, r) || !contains(x, z + 1, r);
    }
    function radiusAt(y) {
        if (y <= spring) return radius;
        return radius * Math.sqrt(1 - ((y - spring) / (rise + 0.5)) ** 2);
    }
    for (let y = 0; y < height; y++) {
        const r = radiusAt(y);
        for (let z = 0; z < diameter; z++) for (let x = 0; x < diameter; x++) {
            if (!contains(x, z, r)) continue;
            const pos = [x, y, z];
            volume.set(pos.join(','), pos);
            const rim = edge(x, z, r);
            if (y === 0 || y === floor) {
                const light = (x - center) % 5 === 0 && (z - center) % 5 === 0
                    && (!isHub || x !== center || z !== center);
                put(x, y, z, light ? lantern : y === floor ? floorSurface : deck);
            } else if (y <= spring && rim) {
                const diagonal = Math.min(Math.abs(x - center), Math.abs(z - center)) >= Math.max(2, radius * .36);
                const window = y >= floor + 2 && y <= floor + 3 && (diagonal || id.startsWith('greenhouse'));
                put(x, y, z, window ? glass : y <= floor || y === spring ? cutStone : stone);
            } else if (y > spring && (y === height - 1 || rim || !contains(x, z, radiusAt(y + 1)))) {
                const dx = Math.abs(x - center), dz = Math.abs(z - center);
                const greenhouse = id.startsWith('greenhouse');
                const skylight = greenhouse
                    ? dx > 1 && dz > 1 && y > spring + 1
                    : y === height - 1 && dx + dz <= 3 && dx + dz > 0;
                put(x, y, z, skylight ? glass : stone);
            }
        }
    }
    // Preserve useful interior markings and furniture from the original room.
    if (!isHub) {
        const shiftX = (diameter - oldWidth) / 2;
        const shiftZ = (diameter - oldDepth) / 2;
        const markings = new Set(['cyan_concrete', 'green_concrete', 'yellow_concrete', 'dirt', 'sea_lantern']);
        for (const {pos: [x, y, z], block} of source.blocks.values()) {
            const px = x + shiftX, pz = z + shiftZ;
            if (!contains(px, pz, radius - 1) || y < floor || y >= Math.min(oldHeight - 1, spring)) continue;
            if (markings.has(block) || y > floor && id.startsWith('reactor_bunker')
                    && (block === 'tinted_glass' || block === 'obsidian')) {
                put(px, y, pz, block);
            }
        }
    } else {
        const ring = Math.min(4, radius - 2);
        for (let dz = -ring; dz <= ring; dz++) for (let dx = -ring; dx <= ring; dx++) {
            const distance = Math.hypot(dx, dz);
            if (distance >= ring - .55 && distance <= ring + .18) {
                put(center + dx, floor, center + dz, dx === 0 || dz === 0 ? gold : 'orbital_architect:deck_plate');
            }
        }
    }
    // Reach the service gallery from every chamber using the same stair orientation.
    const stairZ = center - 2;
    for (let step = 0; step < floor; step++) {
        const x = center - 3 + step, y = floor - step;
        put(x, y, stairZ, 'orbital_architect:technical_stairs[facing=west]');
        put(x, y + 1, stairZ, null);
        put(x, y + 2, stairZ, null);
    }
    for (const [face, dx, dz] of [['north', 0, -1], ['south', 0, 1], ['west', -1, 0], ['east', 1, 0]]) {
        if (!source.ports[face]) continue;
        const x = center + dx * center - (dz === 0 ? 0 : 1);
        const z = center + dz * center - (dx === 0 ? 0 : 1);
        ports[face] = [x, floor + 1, z];
        const width = largePorts.has(face) ? 4 : 2;
        const point = (length, col, y) => [x - dx * length + (dz === 0 ? 0 : col), y,
            z - dz * length + (dx === 0 ? 0 : col)];
        // Two room entrances meet to form a covered corridor with a gallery below.
        for (let length = 0; length <= 5; length++) for (let col = -1; col <= 4; col++) {
            for (let y = 0; y <= floor + 5; y++) {
                const pos = point(length, col, y);
                if (length > 3 && !contains(pos[0], pos[2], radius)) continue;
                volume.set(pos.join(','), pos);
                if (y === 0 || y === floor) {
                    if (length <= 3) put(...pos, y === floor && length === 0 && col >= 0 && col < width ? plate : deck);
                } else if (y === floor + 5) {
                    if (length <= 3) put(...pos, length === 2 && col === 1 ? lantern : stone);
                } else if (col === -1 || col === 4 || length === 0) {
                    const window = length > 0 && y >= floor + 2 && y <= floor + 3;
                    if (length <= 3) put(...pos, window ? glass : stone);
                } else {
                    put(...pos, null);
                }
            }
        }
        for (let col = 0; col < width; col++) {
            for (let row = 0; row < width; row++) put(...point(0, col, floor + 1 + row), null);
        }
        put(...point(0, 0, 2), plate);
        put(...point(0, -1, floor + 2), lantern);
        put(...point(0, width, floor + 2), lantern);
        put(...point(0, 0, floor + width + 1), trim);
        put(...point(0, -1, floor + 1), gold);
    }
    if (source.ports.down) {
        ports.down = [center, 0, center];
        put(...ports.down, null);
    }
    if (source.ports.up) {
        ports.up = [center, height - 1, center];
        put(...ports.up, null);
    }
    if (id.startsWith('ae2_core')) {
        const outer = Math.min(radius - 2, id.endsWith('_xl') ? 11 : 5);
        for (let dz = -outer; dz <= outer; dz++) for (const dx of [-5, -2, 2, 5]) {
            if (contains(center + dx, center + dz, radius - 1) && Math.abs(dz) > 1) {
                put(center + dx, floor, center + dz, 'cyan_concrete');
            }
        }
    }
    if (id.startsWith('logistics')) {
        for (let dz = -radius + 2; dz <= radius - 2; dz++) for (const dx of [-4, 4]) {
            if (contains(center + dx, center + dz, radius - 1)) {
                put(center + dx, floor, center + dz, plate);
            }
        }
    }
    if (id.startsWith('reactor_bunker')) {
        const platform = floor + 4;
        for (let z = center - 4; z <= center + 4; z++) for (let x = center + 3; x <= center + 6; x++) {
            if (contains(x, z, radius - 1)) put(x, platform, z, x === center + 6 && z % 3 === 0 ? lantern : deck);
        }
        for (let step = 1; step <= 4; step++) {
            put(center + 5, floor + step, center + 8 - step, 'orbital_architect:technical_stairs[facing=north]');
        }
    }
    if (id === 'lift_tower') {
        const station = 'orbital_architect:lift_station';
        const levels = [0, floor, floor + 7];
        for (const y of levels) {
            if (y > floor) {
                for (let dz = -4; dz <= 4; dz++) for (let dx = -4; dx <= 4; dx++) {
                    if (dx ** 2 + dz ** 2 > 24) continue;
                    put(center + dx, y, center + dz, Math.abs(dx) === 4 || Math.abs(dz) === 4 ? lantern : deck);
                    if (dx ** 2 + dz ** 2 > 14) put(center + dx, y + 1, center + dz, glass);
                }
            }
            put(center, y, center, station);
            put(center, y + 1, center, null);
            put(center, y + 2, center, null);
        }
    }
    return { size: [diameter, height, diameter], ports, blocks, volume, largePorts, roofFrom: spring + 1 };
}

function save(id, title, tier, original, legacy = false) {
    const isHub = id === 'hub' || id.startsWith('hub_t') || id === 'capsule';
    if (!legacy && (id.startsWith('hub_t') || id === 'stone_module')) save(id, title, tier, original, true);
    const source = withGallery(original);
    const plan = legacy ? source : roundPlan(source, id, tier, isHub);
    const [width, height, depth] = plan.size;
    const floor = plan.ports.north[1] - 1;
    const belt = floor + 3;
    if (legacy && !isHub && belt < height - 1) {
        const trim = 'orbital_architect:hub_trim_' + tier;
        const border = (x, z) => {
            const cell = plan.blocks.get([x, belt, z].join(','));
            if (cell && cell.block !== 'glass' && cell.block !== 'tinted_glass') plan.put(x, belt, z, trim);
        };
        for (let x = 0; x < width; x++) { border(x, 0); border(x, depth - 1); }
        for (let z = 1; z < depth - 1; z++) { border(0, z); border(width - 1, z); }
    }
    const lightingLevels = !legacy ? [] : isHub ? [0] : new Set([0, floor, height - 1]);
    for (const y of lightingLevels) {
        for (let x = 2; x < width - 1; x += 5) {
            for (let z = 2; z < depth - 1; z += 5) {
                const cell = plan.blocks.get([x, y, z].join(","));
                if (cell && !cell.block.includes("[")
                        && !["yellow_concrete", "cyan_concrete", "green_concrete", "glass", "tinted_glass"].includes(cell.block)) {
                    plan.put(x, y, z, "orbital_architect:strip_light");
                }
            }
        }
    }
    if (legacy) plan.openings();
    const jamb = isHub ? 'orbital_architect:hull_panel' : 'orbital_architect:deck_plate';
    for (const [direction, [x, y, z]] of Object.entries(plan.ports)) {
        if (!legacy || !horizontal(direction)) continue;
        const size = plan.largePorts.has(direction) ? 4 : 2;
        const alongX = direction === 'north' || direction === 'south';
        const frame = (offset, dy, material) => {
            const point = [x + (alongX ? offset : 0), y + dy, z + (alongX ? 0 : offset)];
            if (plan.blocks.has(point.join(','))) plan.put(...point, material);
        };
        for (const side of [-1, size]) {
            for (let row = 0; row < size; row++) {
                frame(side, row, row === 1 ? 'orbital_architect:dock_light' : jamb);
            }
        }
        for (let col = 0; col < size; col++) {
            frame(col, -1, 'orbital_architect:hazard_plate');
            frame(col, size, 'orbital_architect:hub_trim_' + tier);
        }
    }
    const structure = legacy ? 'legacy/' + id : id;
    if (plan.volume) {
        for (const [key, pos] of plan.volume) {
            if (!plan.blocks.has(key)) plan.blocks.set(key, { pos, block: 'air' });
        }
    }
    const blocks = [...plan.blocks.values()].sort((a, b) =>
        a.pos[1] - b.pos[1] || a.pos[2] - b.pos[2] || a.pos[0] - b.pos[0]);
    const palette = [...new Set(blocks.map(({ block }) => block))];
    const skins = {
        polished_deepslate: "orbital_architect:deck_plate",
        white_concrete: "orbital_architect:hull_panel",
        yellow_concrete: "orbital_architect:hazard_plate",
        sea_lantern: "orbital_architect:orbital_light",
        cobblestone: "orbital_architect:ribbed_wall",
        stone_bricks: "orbital_architect:ribbed_wall",
        smooth_stone: "orbital_architect:technical_floor",
        obsidian: "orbital_architect:reinforced_shell",
        glass: "orbital_architect:observation_glass",
    };
    const vanilla = new Set(["air", "oak_planks", "dirt", "cyan_concrete", "green_concrete", "tinted_glass"]);
    const ids = palette.map(block => {
        const [base, properties] = block.replace(/\]$/, "").split("[");
        const id = base.includes(":") ? base : skins[base] ?? ("minecraft:" + base);
        if (id.startsWith("minecraft:") && !vanilla.has(id.slice(10))) {
            throw new Error("Material vanilla não previsto: " + id);
        }
        if (id.startsWith("orbital_architect:") && !existsSync(new URL(
                "../../assets/orbital_architect/blockstates/" + id.substring("orbital_architect:".length) + ".json", data))) {
            throw new Error("Material do mod sem blockstate: " + id);
        }
        return { id, properties: properties ? Object.fromEntries(properties.split(",").map(pair => pair.split("="))) : {} };
    });
    const parts = [];
    const byte = value => parts.push(Buffer.from([value]));
    const int = value => {
        const bytes = Buffer.alloc(4);
        bytes.writeInt32BE(value);
        parts.push(bytes);
    };
    const string = value => {
        const data = Buffer.from(value);
        const length = Buffer.alloc(2);
        length.writeUInt16BE(data.length);
        parts.push(length, data);
    };
    const tag = (type, name) => { byte(type); string(name); };
    const list = (name, type, length) => { tag(9, name); byte(type); int(length); };

    tag(10, '');
    tag(3, 'DataVersion');
    int(3955);
    list('size', 3, 3);
    plan.size.forEach(int);
    list('palette', 10, palette.length);
    for (const { id, properties } of ids) {
        tag(8, 'Name');
        string(id);
        if (Object.keys(properties).length > 0) {
            tag(10, 'Properties');
            for (const [key, value] of Object.entries(properties)) {
                tag(8, key);
                string(value);
            }
            byte(0);
        }
        byte(0);
    }
    list('blocks', 10, blocks.length);
    for (const { pos, block } of blocks) {
        list('pos', 3, 3);
        pos.forEach(int);
        tag(3, 'state');
        int(palette.indexOf(block));
        byte(0);
    }
    list('entities', 10, 0);
    byte(0);
    writeFileSync(new URL('structure/' + structure + '.nbt', data), gzipSync(Buffer.concat(parts)));
    const airlocks = Object.fromEntries(Object.keys(plan.ports).filter(horizontal)
        .map(direction => [direction, plan.largePorts.has(direction) ? 4 : 2]));
    writeFileSync(new URL((legacy ? 'orbital_rooms_legacy/' : 'orbital_rooms/') + id + '.json', data), JSON.stringify({
        title, template: 'orbital_architect:' + structure, tier, size: plan.size, ports: plan.ports,
        gallery: true, airlocks, ...(!legacy && { shapedClearance: true, roofFrom: plan.roofFrom }),
    }, null, 2) + '\n');
    console.log(id, plan.size.join('×'), blocks.length + ' blocos');
}

const greenhouse = room(15, 7, 15, 0, 'glass', 'oak_planks', 'smooth_stone', 'glass');
for (let z = 3; z <= 11; z++) {
    for (let x = 3; x <= 11; x++) {
        greenhouse.put(x, 1, z, x === 7 && z === 7 ? 'yellow_concrete' : 'dirt');
    }
    for (const x of [2, 12]) greenhouse.put(x, 0, z, 'cyan_concrete');
    for (const x of [1, 13]) greenhouse.put(x, 1, z, 'oak_planks');
}
for (const z of [2, 12]) {
    for (let x = 1; x <= 13; x++) {
        if (x !== 7) greenhouse.put(x, 0, z, 'green_concrete');
    }
}
for (const x of [3, 7, 11]) {
    for (let z = 1; z < 14; z++) {
        greenhouse.put(x, 6, z, z === 3 || z === 11 ? 'sea_lantern' : 'oak_planks');
    }
}
save('greenhouse', 'Estufa 9×9', 2, greenhouse);

const core = room(19, 14, 19, 8, 'white_concrete', 'polished_deepslate', 'smooth_stone', 'white_concrete');
for (let z = 6; z <= 12; z++) {
    for (let x = 6; x <= 12; x++) core.put(x, 8, z, null);
}
for (let i = 5; i <= 13; i++) {
    for (const edge of [5, 13]) {
        core.put(edge, 8, i, 'cyan_concrete');
        core.put(i, 8, edge, 'cyan_concrete');
    }
}
for (const x of [2, 16]) {
    for (let z = 4; z <= 14; z++) {
        if (z === 9) continue;
        core.put(x, 8, z, 'cyan_concrete');
        core.put(x, 13, z, z % 3 === 1 ? 'sea_lantern' : 'white_concrete');
    }
    core.put(x, 8, 3, null);
    core.put(x, 8, 15, null);
}
save('ae2_core', 'Núcleo AE2', 3, core);

const logistics = room(19, 10, 15, 4, 'white_concrete', 'polished_deepslate', 'smooth_stone', 'smooth_stone');
for (const x of [4, 8, 12, 16]) {
    for (let z = 3; z <= 11; z++) {
        if (z === 7) continue;
        logistics.put(x, 4, z, 'yellow_concrete');
        if (z % 4 === 3) {
            logistics.put(x, 9, z, 'sea_lantern');
            logistics.put(x, 4, z, 'white_concrete');
        }
    }
}
for (const x of [2, 16]) {
    for (const z of [2, 12]) logistics.put(x, 4, z, null);
}
save('logistics', 'Galpão logístico', 2, logistics);

const bunker = room(25, 23, 31, 0, 'stone_bricks', 'polished_deepslate', 'polished_deepslate', 'stone_bricks');
for (let y = 0; y <= 21; y++) {
    for (let z = 1; z < 30; z++) {
        for (let x = 1; x < 24; x++) {
            const lining = y === 0 || y === 21 || x === 1 || x === 23 || z === 1 || z === 29;
            if (lining) bunker.put(x, y, z, 'obsidian');
        }
    }
}
// Front observation partition, separate from the machine envelope.
for (let y = 1; y <= 20; y++) {
    for (let x = 2; x <= 22; x++) {
        const passage = (x === 12 || x === 13) && y <= 2;
        const window = x >= 5 && x <= 19 && y >= 2 && y <= 4 && x !== 12;
        bunker.put(x, y, 5, passage ? null : window ? 'tinted_glass' : 'stone_bricks');
    }
}
for (const [x, z] of [[12, 1], [12, 29], [1, 15], [23, 15]]) {
    for (const y of [1, 2]) {
        bunker.put(x, y, z, null);
        bunker.put(x + (z === 1 || z === 29 ? 1 : 0), y, z + (x === 1 || x === 23 ? 1 : 0), null);
    }
}
bunker.put(12, 21, 15, null);
for (let z = 7; z <= 26; z++) {
    for (let x = 3; x <= 22; x++) {
        if (x === 3 || x === 22 || z === 7 || z === 26) bunker.put(x, 0, z, 'yellow_concrete');
    }
}
for (const x of [3, 7, 12, 17, 22]) {
    for (const z of [3, 9, 15, 21, 27]) bunker.put(x, 21, z, 'sea_lantern');
    bunker.put(x, 0, 3, 'sea_lantern');
}
save('reactor_bunker', 'Bunker de reator', 3, bunker);


function rectangle(plan, x, z, width, depth, y, block) {
    for (let row = 0; row < depth; row++) {
        for (let col = 0; col < width; col++) {
            const edge = row === 0 || col === 0 || row === depth - 1 || col === width - 1;
            if (edge) plan.put(x + col, y, z + row, block);
        }
    }
}

const conservatory = room(45, 13, 45, 0, 'glass', 'white_concrete', 'smooth_stone', 'glass');
for (const centerX of [8, 22, 36]) {
    for (const centerZ of [8, 22, 36]) {
        rectangle(conservatory, centerX - 6, centerZ - 6, 13, 13, 0, 'green_concrete');
        for (let dz = -4; dz <= 4; dz++) {
            for (let dx = -4; dx <= 4; dx++) {
                const center = dx === 0 && dz === 0;
                conservatory.put(centerX + dx, 1, centerZ + dz, center ? 'yellow_concrete' : 'dirt');
            }
            for (const side of [-5, 5]) {
                conservatory.put(centerX + side, 0, centerZ + dz, 'cyan_concrete');
            }
        }
    }
}
for (let x = 1; x < 44; x++) {
    const roof = 8 + Math.floor(Math.min(x, 44 - x) / 5);
    for (let z = 1; z < 44; z++) {
        conservatory.put(x, 12, z, null);
        const rib = z % 7 === 0 || x % 5 === 0;
        conservatory.put(x, roof, z, rib ? 'white_concrete' : 'glass');
        if (x % 5 === 0 && x < 22 || (44 - x) % 5 === 0 && x > 22) {
            conservatory.put(x, roof - 1, z, 'white_concrete');
        }
        if (rib && z % 7 === 0 && x % 5 === 0) conservatory.put(x, roof - 1, z, 'sea_lantern');
    }
    for (const z of [0, 44]) for (let y = roof + 1; y <= 12; y++) conservatory.put(x, y, z, null);
}
for (const x of [0, 44]) for (let z = 0; z < 45; z++) {
    for (let y = 9; y <= 12; y++) conservatory.put(x, y, z, null);
}
for (const x of [15, 29]) for (const z of [15, 29]) {
    for (let y = 1; y <= 10; y++) conservatory.put(x, y, z, 'white_concrete');
}
save('greenhouse_xl', 'Estufa XL', 3, conservatory);

const dataCenter = room(39, 20, 39, 9, 'white_concrete', 'polished_deepslate', 'smooth_stone', 'white_concrete');
for (let z = 16; z <= 22; z++) for (let x = 16; x <= 22; x++) {
    dataCenter.put(x, 9, z, null);
}
rectangle(dataCenter, 14, 14, 11, 11, 9, 'cyan_concrete');
for (const x of [4, 25]) for (const z of [4, 25]) {
    rectangle(dataCenter, x, z, 10, 10, 9, 'cyan_concrete');
    for (const pillarX of [x, x + 9]) for (const pillarZ of [z, z + 9]) {
        for (let y = 10; y <= 18; y++) dataCenter.put(pillarX, y, pillarZ, 'polished_deepslate');
    }
    dataCenter.put(x + 4, 9, z - 1, null);
}
for (const x of [5, 12, 19, 26, 33]) for (const z of [5, 12, 19, 26, 33]) {
    dataCenter.put(x, 19, z, 'sea_lantern');
    if (x !== 19 || z !== 19) dataCenter.put(x, 9, z, 'sea_lantern');
}
save('ae2_core_xl', 'Centro de dados XL', 3, dataCenter);

const factory = room(53, 16, 39, 5, 'white_concrete', 'polished_deepslate', 'smooth_stone', 'smooth_stone');
for (const x of [4, 20, 36]) for (const z of [4, 23]) {
    rectangle(factory, x, z, 13, 12, 5, 'yellow_concrete');
    factory.put(x + 6, 5, z - 1, null);
    for (const col of [x, x + 12]) {
        for (let row = z; row < z + 12; row++) factory.put(col, 15, row, 'polished_deepslate');
        for (const row of [z, z + 11]) {
            for (let y = 6; y < 15; y++) factory.put(col, y, row, 'polished_deepslate');
        }
    }
    for (const col of [x + 3, x + 6, x + 9]) {
        for (const row of [z + 3, z + 8]) {
            factory.put(col, 15, row, 'sea_lantern');
            factory.put(col, 5, row, 'sea_lantern');
        }
    }
}
factory.largePorts.add('south');
save('logistics_xl', 'Fábrica XL', 3, factory);

const powerStation = room(61, 27, 55, 0, 'stone_bricks', 'polished_deepslate', 'polished_deepslate', 'stone_bricks');
for (let y = 1; y <= 25; y++) {
    for (let z = 1; z < 54; z++) {
        for (let x = 1; x < 60; x++) {
            const lining = x === 1 || x === 59 || z === 1 || z === 53 || y === 25;
            if (lining) powerStation.put(x, y, z, 'obsidian');
        }
    }
}
for (let x = 2; x <= 58; x++) for (let y = 1; y <= 9; y++) {
    const window = y >= 2 && y <= 5 && (x >= 6 && x <= 23 || x >= 37 && x <= 54);
    const passage = x >= 29 && x <= 31 && y <= 3;
    powerStation.put(x, y, 12, passage ? null : window ? 'tinted_glass' : 'polished_deepslate');
}
for (const x of [5, 36]) rectangle(powerStation, x, 17, 20, 20, 0, 'yellow_concrete');
rectangle(powerStation, 25, 14, 11, 28, 0, 'cyan_concrete');
for (const [x, z] of [[30, 1], [30, 53], [1, 27], [59, 27]]) {
    for (const y of [1, 2]) {
        powerStation.put(x, y, z, null);
        powerStation.put(x + (z === 1 || z === 53 ? 1 : 0), y, z + (x === 1 || x === 59 ? 1 : 0), null);
    }
}
powerStation.put(30, 25, 27, null);
for (const x of [5, 14, 23, 30, 37, 46, 55]) {
    for (const z of [6, 15, 24, 33, 42, 51]) powerStation.put(x, 25, z, 'sea_lantern');
}
save('reactor_bunker_xl', 'Central de energia XL', 3, powerStation);


const hub = room(13, 6, 13, 0, 'orbital_architect:ceramic_bulkhead', 'orbital_architect:ribbed_wall',
    'orbital_architect:technical_floor', 'orbital_architect:ceramic_bulkhead');
delete hub.ports.up;
delete hub.ports.down;
rectangle(hub, 3, 3, 7, 7, 0, 'orbital_architect:hull_panel');
rectangle(hub, 4, 4, 5, 5, 0, 'smooth_stone');
for (const edge of [0, 12]) {
    for (const offset of [1, 2, 3, 9, 10, 11]) {
        for (const y of [2, 3]) {
            hub.put(edge, y, offset, 'glass');
            hub.put(offset, y, edge, 'glass');
        }
    }
    for (const offset of [5, 7]) {
        for (const y of [1, 2, 3]) {
            const frame = y === 2 ? 'orbital_architect:dock_light' : 'orbital_architect:hull_panel';
            hub.put(edge, y, offset, frame);
            hub.put(offset, y, edge, frame);
        }
    }
    hub.put(edge, 3, 6, 'orbital_architect:hull_panel');
    hub.put(6, 3, edge, 'orbital_architect:hull_panel');
    for (let offset = 0; offset <= 12; offset++) {
        hub.put(edge, 5, offset, 'orbital_architect:hull_panel');
        hub.put(offset, 5, edge, 'orbital_architect:hull_panel');
    }
}
for (const x of [3, 9]) for (const z of [3, 9]) {
    for (let y = 1; y < 5; y++) {
        hub.put(x, y, z, y === 1 ? 'orbital_architect:deck_plate' : 'orbital_architect:hull_panel');
    }
}
for (let x = 4; x <= 8; x++) for (let z = 4; z <= 8; z++) hub.put(x, 5, z, 'glass');
for (const [x, z] of [[6, 1], [6, 11], [1, 6], [11, 6]]) hub.put(x, 0, z, 'orbital_architect:dock_light');
for (const [x, z] of [[4, 4], [4, 8], [8, 4], [8, 8]]) hub.put(x, 0, z, 'orbital_architect:orbital_light');
for (const i of [2, 4, 8, 10]) {
    hub.put(6, 0, i, 'smooth_stone');
    hub.put(i, 0, 6, 'smooth_stone');
}
for (const z of [3, 9]) {
    for (let x = 3; x <= 9; x++) {
        hub.put(x, 4, z, x >= 5 && x <= 7 ? 'orbital_architect:strip_light' : 'orbital_architect:hull_panel');
    }
}
for (const x of [3, 9]) {
    for (let z = 4; z <= 8; z++) {
        hub.put(x, 4, z, z >= 5 && z <= 7 ? 'orbital_architect:strip_light' : 'orbital_architect:hull_panel');
    }
}
const hubTitles = ['Núcleo Pioneiro', 'Núcleo Engenheiro', 'Núcleo Rede ME', 'Núcleo Protótipo'];
for (let tier = 1; tier <= 4; tier++) {
    const module = room(13, 9, 13, 3, 'orbital_architect:ceramic_bulkhead',
        'orbital_architect:ribbed_wall', 'orbital_architect:technical_floor', 'orbital_architect:ceramic_bulkhead');
    delete module.ports.up;
    delete module.ports.down;
    const accent = 'orbital_architect:hub_trim_' + tier;
    for (const {pos: [x, y, z], block} of hub.blocks.values()) {
        const edge = x === 0 || x === 12 || z === 0 || z === 12;
        const interiorPillar = (x === 3 || x === 9) && (z === 3 || z === 9);
        let material = block;
        if ((edge && y === 4 && (x === 0 || x === 12) && (z === 0 || z === 12))
                || (interiorPillar && y === 2)) material = accent;
        if (tier >= 2 && edge && y === 0) material = accent;
        if (tier >= 3 && y === 5 && (x === 0 || x === 12) && (z === 0 || z === 12)) material = accent;
        if (tier === 4 && edge && y === 1 && block === 'orbital_architect:ribbed_wall') {
            material = 'orbital_architect:reinforced_shell';
        }
        module.put(x, y + 3, z, material);
    }
    save(tier === 1 ? 'hub' : 'hub_t' + tier, hubTitles[tier - 1], tier, module);
}


const starter = room(9, 5, 7, 0, 'oak_planks', 'cobblestone', 'oak_planks', 'oak_planks');
const stoneModule = room(9, 5, 7, 0, 'smooth_stone', 'stone_bricks', 'smooth_stone', 'smooth_stone');
for (const plan of [starter, stoneModule]) {
    for (const x of [0, 8]) for (const z of [1, 5]) plan.put(x, 2, z, 'glass');
    for (const z of [0, 6]) for (const x of [2, 3, 5, 6]) plan.put(x, 2, z, 'glass');
}
save('starter', 'Abrigo de madeira', 1, starter);
save('stone_module', 'Abrigo de pedra', 2, stoneModule);

const capsule = room(11, 6, 11, 0, 'white_concrete', 'polished_deepslate', 'smooth_stone', 'glass');
delete capsule.ports.down;
delete capsule.ports.up;
save('capsule', 'Núcleo Cápsula', 1, capsule);

const liftTower = room(17, 19, 17, 0, 'white_concrete', 'polished_deepslate', 'smooth_stone', 'glass');
delete liftTower.ports.down;
delete liftTower.ports.up;
save('lift_tower', 'Torre de carga e observatório', 2, liftTower);










