package nc.etik.terrain;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import nc.etik.terrain.core.PlanData;

/** Panneau des calques : recherche, cocher/décocher, regroupement par XREF, jeux de calques. */
final class LayerDialog {

    interface Listener {
        void onChanged();

        void onClosed();
    }

    private final Activity act;
    private final PlanData plan;
    private final PlanStore store;
    private final String planId;
    private final Listener listener;
    private final List<Integer> rows = new ArrayList<>();
    private String filter = "";
    private boolean onlyUsed = true;
    private BaseAdapter adapter;
    private TextView summary;

    LayerDialog(Activity act, PlanData plan, PlanStore store, String planId, Listener l) {
        this.act = act;
        this.plan = plan;
        this.store = store;
        this.planId = planId;
        this.listener = l;
    }

    private int dp(float v) {
        return (int) (v * act.getResources().getDisplayMetrics().density + 0.5f);
    }

    private void rebuild() {
        rows.clear();
        String f = filter.toLowerCase(Locale.ROOT);
        for (int i = 0; i < plan.layers.size(); i++) {
            PlanData.Layer l = plan.layers.get(i);
            if (onlyUsed && l.count == 0) continue;
            if (!f.isEmpty() && !l.name.toLowerCase(Locale.ROOT).contains(f)) continue;
            rows.add(i);
        }
        rows.sort((a, b) -> {
            PlanData.Layer x = plan.layers.get(a), y = plan.layers.get(b);
            String gx = x.xref == null ? "" : x.xref, gy = y.xref == null ? "" : y.xref;
            int c = gx.compareToIgnoreCase(gy);
            return c != 0 ? c : x.name.compareToIgnoreCase(y.name);
        });
        int vis = 0, used = 0;
        for (PlanData.Layer l : plan.layers) if (l.count > 0) { used++; if (l.visible) vis++; }
        if (summary != null) summary.setText(vis + " calques affichés sur " + used + " contenant des objets"
                + (filter.isEmpty() ? "" : " · " + rows.size() + " correspondent à « " + filter + " »"));
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void changed() {
        rebuild();
        listener.onChanged();
    }

    void show(String title) {
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(8), dp(16), 0);

        EditText search = new EditText(act);
        search.setHint("Rechercher un calque (ex. EP, TOPO, PRTR)");
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable e) {
                filter = e.toString().trim();
                rebuild();
            }
        });
        root.addView(search);

        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.addView(smallButton("Tout", v -> { for (int i : rows) plan.layers.get(i).visible = true; changed(); }));
        bar.addView(smallButton("Aucun", v -> { for (int i : rows) plan.layers.get(i).visible = false; changed(); }));
        bar.addView(smallButton("Inverser", v -> { for (int i : rows) { PlanData.Layer l = plan.layers.get(i); l.visible = !l.visible; } changed(); }));
        bar.addView(smallButton("État DWG", v -> { for (int i : rows) { PlanData.Layer l = plan.layers.get(i); l.visible = !l.offInDwg; } changed(); }));
        root.addView(bar);

        CheckBox used = new CheckBox(act);
        used.setText("Seulement les calques contenant des objets");
        used.setChecked(true);
        used.setOnCheckedChangeListener((b, c) -> { onlyUsed = c; rebuild(); });
        root.addView(used);

        summary = new TextView(act);
        summary.setTextSize(12);
        summary.setTextColor(0xFF555555);
        root.addView(summary);

        ListView list = new ListView(act);
        adapter = new BaseAdapter() {
            public int getCount() { return rows.size(); }
            public Object getItem(int p) { return rows.get(p); }
            public long getItemId(int p) { return rows.get(p); }

            public View getView(int pos, View convert, ViewGroup parent) {
                LinearLayout row;
                TextView header;
                CheckBox cb;
                View swatch;
                if (convert == null) {
                    row = new LinearLayout(act);
                    row.setOrientation(LinearLayout.VERTICAL);
                    header = new TextView(act);
                    header.setTypeface(Typeface.DEFAULT_BOLD);
                    header.setTextColor(0xFF1F4E79);
                    header.setPadding(0, dp(8), 0, dp(2));
                    row.addView(header);
                    LinearLayout line = new LinearLayout(act);
                    line.setOrientation(LinearLayout.HORIZONTAL);
                    line.setGravity(Gravity.CENTER_VERTICAL);
                    swatch = new View(act);
                    LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(14), dp(14));
                    sp.setMarginEnd(dp(8));
                    line.addView(swatch, sp);
                    cb = new CheckBox(act);
                    line.addView(cb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                    row.addView(line);
                    row.setTag(new Object[]{header, cb, swatch});
                } else {
                    row = (LinearLayout) convert;
                }
                Object[] t = (Object[]) row.getTag();
                header = (TextView) t[0];
                cb = (CheckBox) t[1];
                swatch = (View) t[2];
                int idx = rows.get(pos);
                PlanData.Layer l = plan.layers.get(idx);
                String grp = l.xref == null ? "" : l.xref;
                String prev = pos == 0 ? null : (plan.layers.get(rows.get(pos - 1)).xref == null ? "" : plan.layers.get(rows.get(pos - 1)).xref);
                boolean showHeader = pos == 0 ? !grp.isEmpty() || hasXrefRows() : !grp.equals(prev);
                header.setVisibility(showHeader ? View.VISIBLE : View.GONE);
                header.setText(grp.isEmpty() ? "Plan principal" : "XREF : " + grp);
                String name = l.xref == null ? l.name : l.name.substring(l.xref.length() + 1);
                cb.setOnCheckedChangeListener(null);
                cb.setChecked(l.visible);
                cb.setText(name + (l.count > 0 ? "  (" + l.count + ")" : "") + (l.offInDwg ? "  · éteint dans le DWG" : ""));
                cb.setOnCheckedChangeListener((b, c) -> { l.visible = c; changed(); });
                GradientDrawable g = new GradientDrawable();
                g.setColor(l.color == 0 ? Color.BLACK : l.color);
                g.setStroke(1, 0xFF888888);
                swatch.setBackground(g);
                return row;
            }
        };
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(380)));
        rebuild();

        AlertDialog d = new AlertDialog.Builder(act)
                .setTitle(title)
                .setView(root)
                .setPositiveButton("OK", null)
                .setNeutralButton("Jeux de calques…", null)
                .create();
        d.setOnDismissListener(x -> listener.onClosed());
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> showSets()));
        d.show();
    }

    private boolean hasXrefRows() {
        for (int i : rows) if (plan.layers.get(i).xref != null) return true;
        return false;
    }

    private Button smallButton(String text, View.OnClickListener l) {
        Button b = new Button(act);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return b;
    }

    private void showSets() {
        List<String> names = store.layerSetNames(planId);
        List<String> items = new ArrayList<>(names);
        items.add("+ Enregistrer l'affichage actuel…");
        new AlertDialog.Builder(act)
                .setTitle("Jeux de calques")
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    if (which == names.size()) askSetName();
                    else {
                        String n = names.get(which);
                        new AlertDialog.Builder(act).setTitle(n)
                                .setPositiveButton("Appliquer", (x, y) -> { store.applyLayerSet(planId, n, plan); changed(); })
                                .setNegativeButton("Supprimer", (x, y) -> store.deleteLayerSet(planId, n))
                                .setNeutralButton("Annuler", null).show();
                    }
                })
                .show();
    }

    private void askSetName() {
        EditText e = new EditText(act);
        e.setHint("Nom (ex. Réseaux, Voirie seule)");
        e.setSingleLine(true);
        LinearLayout box = new LinearLayout(act);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(act).setTitle("Enregistrer le jeu de calques")
                .setView(box)
                .setPositiveButton("Enregistrer", (d, w) -> {
                    String n = e.getText().toString().trim();
                    if (!n.isEmpty()) store.saveLayerSet(planId, n, plan);
                })
                .setNegativeButton("Annuler", null).show();
    }
}
