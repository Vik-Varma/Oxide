//! Per-section block and biome data in the layout the protocol sends.

use steel_core::chunk::paletted_container::{BiomePalette, BlockPalette};
use steel_utils::BlockStateId;

/// One section's block states, in vanilla's `(y << 8) | (z << 4) | x` order.
pub enum SectionStates {
    /// Every block is this state.
    Single(BlockStateId),
    /// A palette of distinct states and one palette index per block.
    Paletted {
        palette: Vec<BlockStateId>,
        indices: Box<[u16; 4096]>,
    },
}

impl SectionStates {
    pub fn read(states: &BlockPalette) -> Self {
        let mut palette: Vec<BlockStateId> = Vec::new();
        let mut indices = Box::new([0u16; 4096]);
        for y in 0..16 {
            for z in 0..16 {
                for x in 0..16 {
                    let index = palette_index(&mut palette, states.get(x, y, z));
                    indices[(y << 8) | (z << 4) | x] = index as u16;
                }
            }
        }
        if palette.len() == 1 {
            Self::Single(palette[0])
        } else {
            Self::Paletted { palette, indices }
        }
    }
}

/// One section's 4×4×4 biome cells: worker biome ids and one palette index per cell,
/// in `(y << 4) | (z << 2) | x` order.
pub struct SectionBiomes {
    pub palette: Vec<u16>,
    pub indices: [u8; 64],
}

impl SectionBiomes {
    pub fn read(biomes: &BiomePalette) -> Self {
        let mut palette: Vec<u16> = Vec::new();
        let mut indices = [0u8; 64];
        for y in 0..4 {
            for z in 0..4 {
                for x in 0..4 {
                    let index = palette_index(&mut palette, biomes.get(x, y, z));
                    indices[(y << 4) | (z << 2) | x] = index as u8;
                }
            }
        }
        Self { palette, indices }
    }
}

fn palette_index<T: PartialEq + Copy>(palette: &mut Vec<T>, value: T) -> usize {
    if let Some(index) = palette.iter().position(|known| *known == value) {
        return index;
    }
    palette.push(value);
    palette.len() - 1
}
