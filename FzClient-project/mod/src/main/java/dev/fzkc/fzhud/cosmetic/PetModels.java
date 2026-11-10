package dev.fzkc.fzhud.cosmetic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.entity.Entity;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;

/**
 * Pets that Minecraft 1.8.9 doesn't have (axolotl, parrot), built like the newer versions' models, with textures
 * painted here (no image files, so every colour is just a few numbers).
 */
final class PetModels {
    private PetModels() {}

    /** Axolotl: body, head with three frilly gills, a flat swaying tail and four little legs (feet at y = 24). */
    static final class Axolotl extends ModelBase {
        final ModelRenderer body, head, gillTop, gillLeft, gillRight, tail;
        final ModelRenderer[] legs = new ModelRenderer[4];

        Axolotl() {
            textureWidth = 64;
            textureHeight = 64;
            body = new ModelRenderer(this, 0, 11);
            body.addBox(-4f, -2f, -5f, 8, 4, 10);
            body.setRotationPoint(0f, 20f, 0f);
            ModelRenderer fin = new ModelRenderer(this, 40, 12);
            fin.addBox(0f, -4f, -3f, 0, 2, 9);
            body.addChild(fin);
            head = new ModelRenderer(this, 0, 0);
            head.addBox(-4f, -3f, -5f, 8, 5, 5);
            head.setRotationPoint(0f, 20f, -5f);
            gillTop = new ModelRenderer(this, 28, 0);
            gillTop.addBox(-4f, -6f, -1f, 8, 3, 0);
            gillLeft = new ModelRenderer(this, 28, 4);
            gillLeft.addBox(0f, -5f, -1f, 3, 7, 0);
            gillLeft.setRotationPoint(4f, 0f, 0f);
            gillRight = new ModelRenderer(this, 36, 4);
            gillRight.addBox(-3f, -5f, -1f, 3, 7, 0);
            gillRight.setRotationPoint(-4f, 0f, 0f);
            head.addChild(gillTop);
            head.addChild(gillLeft);
            head.addChild(gillRight);
            tail = new ModelRenderer(this, 0, 26);
            tail.addBox(0f, -3f, 0f, 0, 5, 12);
            tail.setRotationPoint(0f, 20f, 5f);
            for (int i = 0; i < 4; i++) {
                legs[i] = new ModelRenderer(this, 48 + (i / 2) * 8, (i % 2) * 6);
                legs[i].addBox(-1.5f, 0f, 0f, 3, 5, 0);
                boolean left = i < 2;
                legs[i].setRotationPoint(left ? 4f : -4f, 20.5f, i % 2 == 0 ? -3f : 3f);
                legs[i].rotateAngleZ = left ? -0.8f : 0.8f;
            }
        }

        /** age = ticks (sway), look = degrees the head turns. */
        @Override
        public void render(Entity e, float f0, float f1, float age, float look, float pitch, float scale) {
            head.rotateAngleY = look * (float) Math.PI / 180f;
            tail.rotateAngleY = MathHelper.sin(age * 0.16f) * 0.35f;
            float g = MathHelper.sin(age * 0.11f) * 0.18f;
            gillLeft.rotateAngleY = 0.35f + g;
            gillRight.rotateAngleY = -0.35f - g;
            gillTop.rotateAngleX = -0.2f - g * 0.6f;
            for (int i = 0; i < 4; i++) legs[i].rotateAngleX = MathHelper.sin(age * 0.09f + i * 1.7f) * 0.15f;
            body.render(scale);
            head.render(scale);
            tail.render(scale);
            for (ModelRenderer l : legs) l.render(scale);
        }
    }

    /** Parrot (as in 1.12): slanted body, crest feather, hooked beak, folded wings, tail (feet at y = 24). */
    static final class Parrot extends ModelBase {
        final ModelRenderer body, tail, wingLeft, wingRight, head, legLeft, legRight;

        Parrot() {
            textureWidth = 32;
            textureHeight = 32;
            body = new ModelRenderer(this, 2, 8);
            body.addBox(-1.5f, 0f, -1.5f, 3, 6, 3);
            body.setRotationPoint(0f, 16.5f, -3f);
            body.rotateAngleX = 0.4937f;
            tail = new ModelRenderer(this, 22, 1);
            tail.addBox(-1.5f, -1f, -1f, 3, 4, 1);
            tail.setRotationPoint(0f, 21.07f, 1.16f);
            tail.rotateAngleX = 1.015f;
            wingLeft = new ModelRenderer(this, 19, 8);
            wingLeft.addBox(-0.5f, 0f, -1.5f, 1, 5, 3);
            wingLeft.setRotationPoint(1.5f, 16.94f, -2.76f);
            wingLeft.rotateAngleX = -0.6981f;
            wingRight = new ModelRenderer(this, 19, 8);
            wingRight.addBox(-0.5f, 0f, -1.5f, 1, 5, 3);
            wingRight.setRotationPoint(-1.5f, 16.94f, -2.76f);
            wingRight.rotateAngleX = -0.6981f;
            head = new ModelRenderer(this, 2, 2);
            head.addBox(-1f, -1.5f, -1f, 2, 3, 2);
            head.setRotationPoint(0f, 15.69f, -2.76f);
            ModelRenderer top = new ModelRenderer(this, 10, 0);
            top.addBox(-1f, -0.5f, -2f, 2, 1, 4);
            top.setRotationPoint(0f, -2f, -1f);
            ModelRenderer beak1 = new ModelRenderer(this, 11, 7);
            beak1.addBox(-0.5f, -1f, -0.5f, 1, 2, 1);
            beak1.setRotationPoint(0f, -0.5f, -1.5f);
            ModelRenderer beak2 = new ModelRenderer(this, 16, 7);
            beak2.addBox(-0.5f, 0f, -0.5f, 1, 2, 1);
            beak2.setRotationPoint(0f, -1.75f, -2.45f);
            ModelRenderer feather = new ModelRenderer(this, 2, 18);
            feather.addBox(0f, -4f, -2f, 0, 5, 4);
            feather.setRotationPoint(0f, -2.15f, 0.15f);
            feather.rotateAngleX = -0.2214f;
            head.addChild(top);
            head.addChild(beak1);
            head.addChild(beak2);
            head.addChild(feather);
            legLeft = new ModelRenderer(this, 14, 18);
            legLeft.addBox(-0.5f, 0f, -0.5f, 1, 2, 1);
            legLeft.setRotationPoint(1f, 22f, -1.05f);
            legRight = new ModelRenderer(this, 14, 18);
            legRight.addBox(-0.5f, 0f, -0.5f, 1, 2, 1);
            legRight.setRotationPoint(-1f, 22f, -1.05f);
        }

        @Override
        public void render(Entity e, float f0, float f1, float age, float look, float pitch, float scale) {
            head.rotateAngleY = look * (float) Math.PI / 180f;
            head.rotateAngleX = MathHelper.sin(age * 0.07f) * 0.12f;
            // now and then a little flutter of the wings
            float cycle = age % 90f;
            float flap = cycle < 14f ? MathHelper.sin(cycle * 1.4f) * 0.5f * (1f - cycle / 14f) : 0f;
            wingLeft.rotateAngleZ = -0.05f - Math.abs(flap);
            wingRight.rotateAngleZ = 0.05f + Math.abs(flap);
            tail.rotateAngleX = 1.015f + MathHelper.sin(age * 0.1f) * 0.06f;
            body.render(scale);
            tail.render(scale);
            wingLeft.render(scale);
            wingRight.render(scale);
            head.render(scale);
            legLeft.render(scale);
            legRight.render(scale);
        }
    }

    // ------------------------------------------------------------------ textures

    /** Pixel-to-pixel brightness wobble, the same every time, so the colours don't look flat. */
    private static int shade(int rgb, int x, int y, float amount) {
        int h = x * 73856093 ^ y * 19349663;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        h ^= h >>> 15;
        float f = 1f + ((h & 0xFF) / 255f - 0.5f) * 2f * amount;
        return mul(rgb, f);
    }

    private static int mul(int rgb, float f) {
        int r = Math.min(255, Math.max(0, (int) (((rgb >> 16) & 0xFF) * f)));
        int g = Math.min(255, Math.max(0, (int) (((rgb >> 8) & 0xFF) * f)));
        int b = Math.min(255, Math.max(0, (int) ((rgb & 0xFF) * f)));
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private static void fill(int[] px, int w, int x0, int y0, int x1, int y1, int rgb, float noise) {
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) px[y * w + x] = shade(rgb, x, y, noise);
    }

    private static ResourceLocation upload(String name, int[] px, int w, int h) {
        DynamicTexture t = new DynamicTexture(w, h);
        System.arraycopy(px, 0, t.getTextureData(), 0, px.length);
        t.updateDynamicTexture();
        return Minecraft.getMinecraft().getTextureManager().getDynamicTextureLocation("fzclient_pet_" + name, t);
    }

    /** body = main colour, gill = the frills (and a hint on the tail edge). */
    static ResourceLocation axolotlSkin(String name, int body, int gill) {
        int w = 64;
        int[] px = new int[64 * 64];
        fill(px, w, 0, 0, 64, 64, body, 0.05f);
        // lighter belly (body and head undersides), a slightly darker back
        fill(px, w, 18, 11, 26, 21, mul(body, 1.12f), 0.03f);
        fill(px, w, 13, 0, 21, 5, mul(body, 1.1f), 0.03f);
        fill(px, w, 10, 11, 18, 21, mul(body, 0.94f), 0.06f);
        // the frills: stripes of the gill colour
        for (int y = 0; y < 11; y++) {
            for (int x = 28; x < 44; x++) px[y * w + x] = shade(mul(gill, (x + y) % 3 == 0 ? 1.15f : 1f), x, y, 0.05f);
        }
        // legs a little darker, the tail and back fin fading toward the gill colour at the edge
        fill(px, w, 48, 0, 64, 11, mul(body, 0.9f), 0.05f);
        for (int x = 0; x < 24; x++) {
            px[38 * w + x] = shade(mul(gill, 1.05f), x, 38, 0.04f);
            px[42 * w + x] = shade(mul(gill, 1.05f), x, 42, 0.04f);
        }
        fill(px, w, 40, 21, 58, 22, gill, 0.04f);
        // face (front of the head, x 5..13, y 5..10): eyes at the corners, a small smile
        int eye = 0xFF1A1414;
        px[7 * w + 5] = eye;
        px[7 * w + 12] = eye;
        px[6 * w + 5] = mul(eye, 1.6f);
        px[6 * w + 12] = mul(eye, 1.6f);
        for (int x = 8; x < 10; x++) px[9 * w + x] = mul(body, 0.72f);
        px[8 * w + 7] = mul(body, 0.72f);
        px[8 * w + 10] = mul(body, 0.72f);
        return upload(name, px, w, 64);
    }

    /**
     * body = main colour, wing = the lower wing feathers and tail, stripe = a band across the wings,
     * front = the chest (0 = same as body).
     */
    static ResourceLocation parrotSkin(String name, int body, int wing, int stripe, int front) {
        int w = 32;
        int[] px = new int[32 * 32];
        fill(px, w, 0, 0, 32, 32, body, 0.06f);
        if (front != 0) fill(px, w, 5, 11, 8, 17, front, 0.05f);
        // wings: the main colour on top, a band, then the flight feathers
        fill(px, w, 19, 13, 27, 14, stripe, 0.05f);
        fill(px, w, 19, 14, 27, 16, wing, 0.06f);
        fill(px, w, 22, 1, 30, 6, wing, 0.06f);
        // beak and legs
        fill(px, w, 11, 7, 20, 10, 0xFF3C3A3A, 0.08f);
        fill(px, w, 14, 18, 18, 21, 0xFF6E6060, 0.06f);
        // face: a pale patch with the eye on each side of the head
        fill(px, w, 2, 4, 4, 7, 0xFFF2EEE8, 0.03f);
        fill(px, w, 6, 4, 8, 7, 0xFFF2EEE8, 0.03f);
        px[5 * w + 3] = 0xFF141010;
        px[5 * w + 6] = 0xFF141010;
        return upload(name, px, w, 32);
    }
}
