package dev.px.gui.legacy.setting;

import dev.px.core.layout.Content;

import dev.px.gui.legacy.Component;
import dev.px.gui.legacy.GuiStyle;
import dev.px.gui.legacy.SettingComponent;
import dev.px.gui.legacy.SettingRendererRegistry;
import dev.px.core.input.MouseButton;
import dev.px.core.setting.impl.GroupSetting;

/**
 * A collapsible section of related settings.
 *
 * <p>Rows are built for <em>every</em> child, not only the ones visible at
 * construction. A child gated by {@code visibleWhen} will start returning true
 * later &mdash; that is the whole point of the feature &mdash; and a tree built
 * from a snapshot of what was visible at the time would never show it. Each row
 * reports its own visibility instead, so the tree is built once and the
 * gating is live.
 *
 * <p>The expanded flag is the group setting's own value, so it persists with
 * everything else and a group left open is still open next session.
 */
public final class GroupRow extends SettingComponent<GroupSetting> {

    public GroupRow(GroupSetting setting, SettingRendererRegistry renderers) {
        super(setting);
        for (Component row : renderers.createAll(setting.getChildren())) {
            add(row);
        }
    }

    @Override
    protected boolean showsChildren() {
        return getSetting().isExpanded();
    }

    @Override
    protected float childIndent() {
        return GuiStyle.indent();
    }

    @Override
    protected void content(Content c) {
        boolean expanded = getSetting().isExpanded();
        header(c, expanded, row -> {
            value(row, getSetting().displayValue());
            caret(row, expanded);
        });
    }

    @Override
    protected boolean onClick(float x, float y, MouseButton button) {
        if (button != MouseButton.LEFT) {
            return false;
        }
        getSetting().toggleExpanded();
        return true;
    }
}
