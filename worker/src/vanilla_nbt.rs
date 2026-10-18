//! Converts SteelMC's saved entities and block entities to the NBT vanilla stores
//! in a proto chunk, so the mod can hand them to `ProtoChunk.addEntity` and
//! `ChunkAccess.setBlockEntityNbt` as if the chunk had been loaded from disk.

use std::io::Cursor;

use simdnbt::owned::{BaseNbt, NbtCompound, NbtList, NbtTag};
use steel_core::chunk_saver::{PersistentBlockEntity, PersistentEntity};

/// Writes a compound as a complete named NBT root (`NbtIo.read` on the Java side).
pub fn to_root_bytes(compound: NbtCompound) -> Vec<u8> {
    let mut bytes = Vec::new();
    BaseNbt::new("", compound).write(&mut bytes);
    bytes
}

/// Vanilla `BlockEntity.saveWithFullMetadata` layout: `id`, `x`, `y`, `z` and the
/// type's own data. SteelMC's pending `DUMMY` marker becomes vanilla's `{id: "DUMMY"}`.
pub fn block_entity(
    entity: &PersistentBlockEntity,
    chunk_x: i32,
    chunk_z: i32,
) -> Result<NbtCompound, String> {
    let mut nbt = read_body(&entity.nbt_data)?;
    let id = entity
        .entity_type
        .as_ref()
        .map_or_else(|| "DUMMY".to_owned(), ToString::to_string);
    nbt.insert("id", id);
    nbt.insert("x", chunk_x * 16 + i32::from(entity.x));
    nbt.insert("y", i32::from(entity.y));
    nbt.insert("z", chunk_z * 16 + i32::from(entity.z));
    Ok(nbt)
}

/// Vanilla `Entity.saveWithoutId` layout plus `id`, as `ProtoChunk.addEntity` stores it.
pub fn entity(entity: &PersistentEntity) -> Result<NbtCompound, String> {
    let mut nbt = NbtCompound::new();
    nbt.insert("id", entity.entity_type.to_string());
    nbt.insert("Pos", NbtTag::List(NbtList::Double(entity.pos.to_vec())));
    nbt.insert(
        "Motion",
        NbtTag::List(NbtList::Double(entity.motion.to_vec())),
    );
    nbt.insert(
        "Rotation",
        NbtTag::List(NbtList::Float(entity.rotation.to_vec())),
    );
    nbt.insert("fall_distance", entity.fall_distance);
    nbt.insert("Fire", entity.remaining_fire_ticks as i16);
    nbt.insert("Air", entity.air_supply as i16);
    nbt.insert("OnGround", i8::from(entity.on_ground));
    nbt.insert("Invulnerable", i8::from(entity.invulnerable));
    nbt.insert("PortalCooldown", entity.portal_cooldown);
    nbt.insert("UUID", NbtTag::IntArray(uuid_ints(entity.uuid)));
    if !entity.custom_name_nbt.is_empty() {
        let wrapper = read_body(&entity.custom_name_nbt)?;
        if let Some(name) = wrapper.get("CustomName") {
            nbt.insert("CustomName", name.clone());
        }
    }
    if entity.custom_name_visible {
        nbt.insert("CustomNameVisible", 1i8);
    }
    if entity.silent {
        nbt.insert("Silent", 1i8);
    }
    if entity.no_gravity {
        nbt.insert("NoGravity", 1i8);
    }
    if entity.glowing {
        nbt.insert("Glowing", 1i8);
    }
    if entity.ticks_frozen > 0 {
        nbt.insert("TicksFrozen", entity.ticks_frozen);
    }
    if entity.has_visual_fire {
        nbt.insert("HasVisualFire", 1i8);
    }
    if !entity.tags.is_empty() {
        let tags = entity.tags.iter().map(|tag| tag.as_str().into()).collect();
        nbt.insert("Tags", NbtTag::List(NbtList::String(tags)));
    }
    if !entity.custom_data_nbt.is_empty() {
        let data = read_body(&entity.custom_data_nbt)?;
        if !data.is_empty() {
            nbt.insert("data", data);
        }
    }
    // Vanilla writes the type's own fields (`addAdditionalSaveData`) after the shared
    // ones, into the same output, so on a name clash they win.
    for (name, tag) in read_body(&entity.nbt_data)? {
        nbt.insert(name, tag);
    }
    if !entity.passengers.is_empty() {
        let passengers = entity
            .passengers
            .iter()
            .map(self::entity)
            .collect::<Result<Vec<_>, _>>()?;
        nbt.insert("Passengers", NbtTag::List(NbtList::Compound(passengers)));
    }
    Ok(nbt)
}

/// Vanilla's `UUIDUtil.CODEC`: four big-endian ints, most significant first.
fn uuid_ints(uuid: [u8; 16]) -> Vec<i32> {
    uuid.as_chunks::<4>()
        .0
        .iter()
        .map(|word| i32::from_be_bytes(*word))
        .collect()
}

/// Reads one of SteelMC's NBT blobs: a compound body without a root header, or empty.
fn read_body(bytes: &[u8]) -> Result<NbtCompound, String> {
    if bytes.is_empty() {
        return Ok(NbtCompound::new());
    }
    let base = simdnbt::borrow::read_compound(&mut Cursor::new(bytes))
        .map_err(|error| format!("invalid NBT from SteelMC: {error}"))?;
    Ok(simdnbt::borrow::NbtCompound::from(&base).to_owned())
}
