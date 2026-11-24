package dev.byecrashes;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

public final class DiagnosisScreen extends Screen {
    private final CrashAnalyzer.Diagnosis diag;
    private final Screen parent;
    private final List<OrderedText> lines = new ArrayList<>();
    private final List<Integer> gaps = new ArrayList<>();

    public DiagnosisScreen(CrashAnalyzer.Diagnosis diag, Screen parent) {
        super(Text.literal("ByeCrashes"));
        this.diag = diag;
        this.parent = parent;
    }

    @Override
    protected void init() {
        lines.clear();
        gaps.clear();
        int maxW = Math.min(this.width - 40, 420);
        for (String line : diag.fullText().split("\n")) {
            if (line.isEmpty()) {
                lines.add(null);
                continue;
            }
            lines.addAll(this.textRenderer.wrapLines(Text.literal(line), maxW));
        }

        int w = 150;
        int y = this.height - 28;
        addDrawableChild(ButtonWidget.builder(Text.literal(L.t("Copy report", "نسخ التقرير")), b -> {
            if (this.client != null) this.client.keyboard.setClipboard(diag.fullText());
        }).dimensions(this.width / 2 - w - 4, y, w, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(L.t("Close", "إغلاق")), b -> close())
                .dimensions(this.width / 2 + 4, y, w, 20).build());
    }

    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(parent);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx);
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("ByeCrashes").formatted(Formatting.RED), this.width / 2, 10, 0xFFFFFF);
        int maxW = Math.min(this.width - 40, 420);
        int x = (this.width - maxW) / 2;
        int y = 28;
        int bottom = this.height - 36;
        for (OrderedText o : lines) {
            if (o == null) {
                y += 6;
                continue;
            }
            if (y + 9 > bottom) break;
            ctx.drawTextWithShadow(this.textRenderer, o, x, y, 0xFFFFFF);
            y += 10;
        }
        super.render(ctx, mouseX, mouseY, delta);
    }
}
