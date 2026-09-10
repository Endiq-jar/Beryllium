/*
 * Copyright (c) 2016, 2017, 2018, 2019 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.vulkanmod.render.chunk.build.frapi.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.MatrixUtil;

import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import net.fabricmc.fabric.api.renderer.v1.material.GlintMode;
import net.fabricmc.fabric.api.renderer.v1.model.ModelHelper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.vulkanmod.mixin.render.frapi.ItemRendererAccessor;
import org.jetbrains.annotations.Nullable;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.fabricmc.fabric.api.renderer.v1.material.BlendMode;
import net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.util.TriState;
import net.vulkanmod.render.chunk.build.frapi.helper.ColorHelper;
import net.vulkanmod.render.chunk.build.frapi.mesh.EncodingFormat;
import net.vulkanmod.render.chunk.build.frapi.mesh.MutableQuadViewImpl;

import static net.vulkanmod.render.chunk.build.frapi.render.AbstractBlockRenderContext.STANDARD_MATERIAL;

/**
 * The render context used for item rendering.
 */
@SuppressWarnings("removal")
public class ItemRenderContext extends AbstractRenderContext {
	/** Value vanilla uses for item rendering. The only sensible choice, of course.  */
	private static final long ITEM_RANDOM_SEED = 42L;
	private static final int GLINT_COUNT = ItemStackRenderState.FoilType.values().length;

	private final RandomSource random = RandomSource.create();
	private final Supplier<RandomSource> randomSupplier = () -> {
		random.setSeed(ITEM_RANDOM_SEED);
		return random;
	};

	private final MutableQuadViewImpl editorQuad = new MutableQuadViewImpl() {
		{
			data = new int[EncodingFormat.TOTAL_STRIDE];
			clear();
		}

		@Override
		public void emitDirectly() {
			bufferQuad(this);
		}

		@Override
		public void emitItemQuads(QuadEmitter emitter, BakedModel model, BlockState state,
								  Supplier<RandomSource> randomSupplier) {
//			super.emitItemQuads(emitter, model, state, randomSupplier);
			ItemRenderContext.this.emitItemQuads(model, state, randomSupplier);
		}
	};

	private ItemDisplayContext transformMode;
	private PoseStack matrixStack;
	private MultiBufferSource vertexConsumerProvider;
	private int lightmap;
	private int[] tints;

	private RenderType defaultLayer;
	private ItemStackRenderState.FoilType defaultGlint;

	private PoseStack.Pose specialGlintEntry;
	private final VertexConsumer[] vertexConsumerCache = new VertexConsumer[3 * GLINT_COUNT];

	public ItemRenderContext() {
	}

	public QuadEmitter getEmitter() {
		editorQuad.clear();
		return editorQuad;
	}

	public void renderModel(ItemDisplayContext transformMode, PoseStack matrixStack, MultiBufferSource bufferSource, int lightmap, int overlay, int[] tints, BakedModel model, RenderType renderType, ItemStackRenderState.FoilType foilType) {
		this.transformMode = transformMode;
		this.matrixStack = matrixStack;
		this.vertexConsumerProvider = bufferSource;
		this.lightmap = lightmap;
		this.overlay = overlay;
		this.tints = tints;

		defaultLayer = renderType;
		defaultGlint = foilType;

		matrix = matrixStack.last().pose();
		normalMatrix = matrixStack.last().normal();

		model.emitItemQuads(getEmitter(), randomSupplier);

		this.matrixStack = null;
		this.vertexConsumerProvider = null;
		this.tints = null;

		specialGlintEntry = null;
		Arrays.fill(vertexConsumerCache, null);
	}

	public void emitItemQuads(BakedModel model, @Nullable BlockState state, Supplier<RandomSource> randomSupplier) {
		for (int i = 0; i <= ModelHelper.NULL_FACE_ID; i++) {
			final Direction cullFace = ModelHelper.faceFromIndex(i);
			final List<BakedQuad> quads = model.getQuads(state, cullFace, randomSupplier.get());
			final int count = quads.size();

			for (int j = 0; j < count; j++) {
				final BakedQuad q = quads.get(j);
				editorQuad.fromVanilla(q, STANDARD_MATERIAL, cullFace);

				bufferQuad(editorQuad);
			}
		}
	}

	@Override
	protected void bufferQuad(MutableQuadViewImpl quad) {
		final RenderMaterial mat = quad.material();
		final boolean emissive = mat.emissive();
		final VertexConsumer vertexConsumer = getVertexConsumer(mat.blendMode(), mat.glintMode());

		tintQuad(quad);
		shadeQuad(quad, emissive);
		bufferQuad(quad, vertexConsumer);
	}

	private void tintQuad(MutableQuadViewImpl quad) {
		int tintIndex = quad.tintIndex();

		if (tintIndex != -1 && tintIndex < tints.length) {
			final int tint = tints[tintIndex];

			for (int i = 0; i < 4; i++) {
				quad.color(i, ColorHelper.multiplyColor(tint, quad.color(i)));
			}
		}
	}

	private void shadeQuad(MutableQuadViewImpl quad, boolean emissive) {
		if (emissive) {
			for (int i = 0; i < 4; i++) {
				quad.lightmap(i, LightTexture.FULL_BRIGHT);
			}
		} else {
			final int lightmap = this.lightmap;

			for (int i = 0; i < 4; i++) {
				quad.lightmap(i, ColorHelper.maxBrightness(quad.lightmap(i), lightmap));
			}
		}
	}

	private VertexConsumer getVertexConsumer(BlendMode blendMode, GlintMode glintMode) {
		RenderType layer;
		ItemStackRenderState.FoilType glint;

		if (blendMode == BlendMode.DEFAULT) {
			layer = defaultLayer;
		} else {
			layer = blendMode == BlendMode.TRANSLUCENT ? Sheets.translucentItemSheet() : Sheets.cutoutBlockSheet();
		}

		if (glintMode == GlintMode.DEFAULT) {
			glint = defaultGlint;
		} else {
			glint = glintMode.glint;
		}

		int cacheIndex;

		if (layer == Sheets.translucentItemSheet()) {
			cacheIndex = 0;
		} else if (layer == Sheets.cutoutBlockSheet()) {
			cacheIndex = GLINT_COUNT;
		} else {
			cacheIndex = 2 * GLINT_COUNT;
		}

		cacheIndex += glint.ordinal();
		VertexConsumer vertexConsumer = vertexConsumerCache[cacheIndex];

		if (vertexConsumer == null) {
			vertexConsumer = createVertexConsumer(layer, glint);
			vertexConsumerCache[cacheIndex] = vertexConsumer;
		}

		return vertexConsumer;
	}

	private VertexConsumer createVertexConsumer(RenderType layer, ItemStackRenderState.FoilType glint) {
		if (glint == ItemStackRenderState.FoilType.SPECIAL) {
			if (specialGlintEntry == null) {
				specialGlintEntry = matrixStack.last().copy();

				if (transformMode == ItemDisplayContext.GUI) {
					MatrixUtil.mulComponentWise(specialGlintEntry.pose(), 0.5F);
				} else if (transformMode.firstPerson()) {
					MatrixUtil.mulComponentWise(specialGlintEntry.pose(), 0.75F);
				}
			}

			return ItemRendererAccessor.getCompassFoilBuffer(vertexConsumerProvider, layer, specialGlintEntry);
		}

		return ItemRenderer.getFoilBuffer(vertexConsumerProvider, layer, true, glint != ItemStackRenderState.FoilType.NONE);
	}

}
