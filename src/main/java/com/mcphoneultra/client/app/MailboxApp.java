package com.mcphoneultra.client.app;

import com.mcphoneultra.client.net.MailPackets;
import com.mcphoneultra.client.ui.Ui;
import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.core.client.PhoneSession;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import com.november.mcphone.api.client.ui.PhoneStyle;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 📮 物品郵件：像末影箱＋訊息。好友間寄信，副手＝附件（一疊），
 * 收件人點「領取」把附件收進背包。只能寄給線上玩家。
 */
public final class MailboxApp extends BaseApp {

    public MailboxApp() {
        super("mailbox", false);
    }

    @Override
    protected IPhonePage createPage() {
        return new MailboxPage();
    }

    // ---- S2C 接收（NetworkHandler enqueueWork 調用） ----

    private static volatile List<MailPackets.MailListS2C.MailItem> inbox = List.of();
    private static volatile long inboxAt;

    public static void receiveList(MailPackets.MailListS2C packet) {
        inbox = packet.mails();
        inboxAt = System.currentTimeMillis();
    }

    public static void receiveNotify(MailPackets.MailNotifyS2C packet) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal(
                    "📮 「" + packet.fromName() + "」寄來新郵件" + (packet.attachCount() > 0 ? "（含附件）" : "")), false);
        }
    }

    // ---- 頁面 ----

    private static final class MailboxPage extends ClickablePage {
        private String toName = "";
        private String message = "";
        private boolean writing;
        private boolean typingTo;
        private boolean typingMsg;
        private String toast = "";
        private long toastUntil;
        private List<MailPackets.MailListS2C.MailItem> shown = List.of();

        private void toast(String s) {
            toast = s;
            toastUntil = System.currentTimeMillis() + 2000;
        }

        @Override
        public void onOpen() {
            refresh();
        }

        private void refresh() {
            shown = MailboxApp.inbox;
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(new MailPackets.MailOpenC2S());
        }

        @Override
        public void render(PhoneCanvas c) {
            int x = c.x(), y = c.y(), w = c.width(), h = c.height();
            var s = c.style();
            GuiGraphics g = c.graphics();
            Ui.fill(g, x, y, w, h, s.screenBackground());

            if (writing) {
                renderWrite(c, x, y, w, h, s, g);
                return;
            }
            renderInbox(c, x, y, w, h, s, g);
        }

        private void renderInbox(PhoneCanvas c, int x, int y, int w, int h, PhoneStyle s, GuiGraphics g) {
            Ui.textClipped(c, "📮 信箱（寄件人 / 附件）", x + 3, y + 2, s.titleColor(), x, y, w, 12);
            Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());

            if (clickOn(x + w - 40, y + 13, 38, 12)) {
                writing = true;
                typingTo = true;
            }
            Ui.button(c, x + w - 40, y + 13, 38, 12, true, c.hovered(x + w - 40, y + 13, 38, 12));
            Ui.buttonLabel(c, x + w - 40, y + 13, 38, 12, "✏ 寫信", true);

            if (clickOn(x + 2, y + 13, 40, 12)) refresh();
            Ui.button(c, x + 2, y + 13, 40, 12, true, c.hovered(x + 2, y + 13, 40, 12));
            Ui.buttonLabel(c, x + 2, y + 13, 40, 12, "🔄 刷新", true);

            if (System.currentTimeMillis() - inboxAt > 1500 && shown.isEmpty()) {
                Ui.drawCentered(c, "（沒有郵件）", x, y + 40, w, 12, s.subtleColor());
                return;
            }

            int listY = y + 30, listH = h - 30 - 16, rowH = 16;
            for (int i = 0; i < shown.size(); i++) {
                var m = shown.get(i);
                int ry = listY + i * rowH;
                if (ry + rowH > listY + listH) break;
                Ui.fill(g, x + 2, ry, x + w - 2, ry + rowH - 1,
                        m.claimed() ? 0xFF161B22 : 0xFF1E2A3A);
                Ui.hline(g, x + 2, x + w - 2, ry + rowH - 1, s.buttonDisabledColor());
                String att = m.attachments().isEmpty() ? "" : " 📎" + m.attachments().size();
                String preview = m.message().isEmpty() ? "（無訊息）" : m.message();
                Ui.textClipped(c, "✉ " + m.fromName() + "：" + preview + att,
                        x + 4, ry + 2, m.claimed() ? s.subtleColor() : s.titleColor(), x, ry, w - 8, rowH - 3);
                if (clickOn(x + 2, ry, w - 2, rowH - 1)) {
                    openMail(i, x, y, w, h, s, g);
                }
            }
            Ui.drawCentered(c, "副手拿一疊＝附件；寄給線上玩家", x, y + h - 13, w, 12, s.subtleColor());
        }

        private void openMail(int i, int x, int y, int w, int h, PhoneStyle s, GuiGraphics g) {
            var m = shown.get(i);
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(new MailPackets.MailReadC2S(i));
            // 簡易詳情：toast 顯示訊息全文與附件
            StringBuilder sb = new StringBuilder();
            sb.append(m.fromName()).append("：").append(m.message());
            if (!m.attachments().isEmpty()) {
                sb.append("  📎");
                for (ItemStack st : m.attachments()) {
                    sb.append(" ").append(st.getHoverName().getString()).append("×").append(st.getCount());
                }
                sb.append(" （點「領取」收進背包）");
            }
            toast(sb.length() > 90 ? sb.substring(0, 90) : sb.toString());
            if (!m.attachments().isEmpty()) {
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(new MailPackets.MailTakeC2S(i));
                refresh();
            }
        }

        private void renderWrite(PhoneCanvas c, int x, int y, int w, int h, PhoneStyle s, GuiGraphics g) {
            Ui.textClipped(c, "✏ 寫信", x + 3, y + 2, s.titleColor(), x, y, w, 12);
            Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());

            if (clickOn(x + w - 50, y + 2, 48, 12)) {
                writing = false;
                typingTo = false;
                typingMsg = false;
            }
            Ui.button(c, x + w - 50, y + 2, 48, 12, true, c.hovered(x + w - 50, y + 2, 48, 12));
            Ui.buttonLabel(c, x + w - 50, y + 2, 48, 12, "← 返回", true);

            Ui.text(c, "收件人（線上玩家名）", x + 4, y + 16, s.subtleColor());
            if (clickOn(x + 4, y + 26, w - 8, 12)) typingTo = true;
            Ui.fill(g, x + 4, y + 26, x + w - 4, y + 38, 0xFF000000);
            Ui.textClipped(c, toName.isEmpty() ? "（點這裡輸入名字）" : toName,
                    x + 6, y + 27, s.accentColor(), x, y + 26, w - 8, 12);

            Ui.text(c, "訊息", x + 4, y + 42, s.subtleColor());
            if (clickOn(x + 4, y + 52, w - 8, 12)) typingMsg = true;
            Ui.fill(g, x + 4, y + 52, x + w - 4, y + 64, 0xFF000000);
            Ui.textClipped(c, message.isEmpty() ? "（點這裡輸入訊息）" : message,
                    x + 6, y + 53, s.accentColor(), x, y + 52, w - 8, 12);

            Ui.drawCentered(c, "📎 附件＝你副手拿的那一疊（可空）", x, y + 76, w, 12, s.subtleColor());

            if (clickOn(x + w / 2 - 40, y + 92, 80, 16)) {
                if (toName.trim().isEmpty()) {
                    toast("先填收件人");
                } else {
                    net.neoforged.neoforge.network.PacketDistributor.sendToServer(new MailPackets.MailSendC2S(toName.trim(), message.trim()));
                    writing = false;
                    toast("已寄出");
                    refresh();
                }
            }
            Ui.button(c, x + w / 2 - 40, y + 92, 80, 16, true, c.hovered(x + w / 2 - 40, y + 92, 80, 16));
            Ui.buttonLabel(c, x + w / 2 - 40, y + 92, 80, 16, "📨 寄出", true);

            if (System.currentTimeMillis() < toastUntil && !toast.isEmpty()) {
                Ui.drawCentered(c, toast, x, y + h - 14, w, 12, s.titleColor());
            }
        }

        @Override
        public boolean keyPressed(int key, int scan, int mods) {
            if (typingTo) {
                if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE && !toName.isEmpty()) {
                    toName = toName.substring(0, toName.length() - 1);
                }
                return true;
            }
            if (typingMsg) {
                if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE && !message.isEmpty()) {
                    message = message.substring(0, message.length() - 1);
                }
                return true;
            }
            return false;
        }

        @Override
        public boolean charTyped(char ch, int mods) {
            if (typingTo) {
                if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '-') {
                    if (toName.length() < 16) toName += ch;
                }
                return true;
            }
            if (typingMsg) {
                if (ch >= 32 && ch != 127 && message.length() < 500) message += ch;
                return true;
            }
            return false;
        }

        @Override
        public boolean onBack() {
            if (writing) {
                writing = false;
                typingTo = false;
                typingMsg = false;
                return true;
            }
            return false;
        }

        @Override
        public boolean capturesKeyboard() {
            return typingTo || typingMsg;
        }
    }
}
