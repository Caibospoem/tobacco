package com.fujian.tobacco;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fujian.tobacco.data.model.SupplyItem;

import java.util.ArrayList;
import java.util.List;

class SupplyAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_ITEM = 0;
    private static final int TYPE_HEADER = 1;
    private static final int TYPE_CONDITION = 2;

    private final List<Object> items = new ArrayList<>(); // SupplyItem or String(header)

    void updateData(List<SupplyItem> list, List<SupplyItem> conditions) {
        items.clear();
        if (list != null) items.addAll(list);
        if (conditions != null && !conditions.isEmpty()) {
            items.add("条件投放策略");
            items.addAll(conditions);
        }
        notifyDataSetChanged();
    }

    void setMixedData(List<Object> mixed) {
        items.clear();
        if (mixed != null) items.addAll(mixed);
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int pos) {
        Object obj = items.get(pos);
        if (obj instanceof String) return TYPE_HEADER;
        SupplyItem item = (SupplyItem) obj;
        return item.getProductCode() != null && item.getProductCode().startsWith("COND_")
                ? TYPE_CONDITION : TYPE_ITEM;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            return new HeaderVH(inflater.inflate(R.layout.item_section_header, parent, false));
        }
        View v = inflater.inflate(R.layout.item_supply_card, parent, false);
        return viewType == TYPE_CONDITION ? new ConditionVH(v) : new ItemVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int pos) {
        int type = getItemViewType(pos);
        if (type == TYPE_HEADER) {
            String title = (String) items.get(pos);
            ((HeaderVH) holder).tv.setText(title);
            return;
        }
        SupplyItem item = (SupplyItem) items.get(pos);
        if (type == TYPE_CONDITION) {
            ConditionVH h = (ConditionVH) holder;
            h.brand.setText(item.getBrandName());
            h.desc.setText(item.getSupplyNote());
        } else {
            ItemVH h = (ItemVH) holder;
            h.brand.setText(item.getBrandName());
            int quota = item.getPersonalQuota();
            if (quota > 0) {
                h.quota.setText(quota + " 条");
                h.quota.setTextColor(0xFF2E7D32);
            } else {
                h.quota.setText("--");
                h.quota.setTextColor(0xFF999999);
            }

            StringBuilder sub = new StringBuilder();
            if (!isEmpty(item.getTierCategory())) sub.append(item.getTierCategory());
            if (!isEmpty(item.getRegion())) {
                if (sub.length() > 0) sub.append(" · ");
                sub.append(item.getRegion());
            }
            if (!isEmpty(item.getSupplyNote())) {
                if (sub.length() > 0) sub.append(" · ");
                sub.append(item.getSupplyNote());
            }
            if (sub.length() > 0) {
                h.subtitle.setText(sub);
                h.subtitle.setVisibility(View.VISIBLE);
            } else {
                h.subtitle.setVisibility(View.GONE);
            }
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private boolean isEmpty(String s) { return s == null || s.trim().isEmpty(); }

    static class ItemVH extends RecyclerView.ViewHolder {
        TextView brand, subtitle, quota;
        ItemVH(View v) {
            super(v);
            brand = v.findViewById(R.id.tv_brand);
            subtitle = v.findViewById(R.id.tv_subtitle);
            quota = v.findViewById(R.id.tv_quota);
        }
    }

    static class ConditionVH extends RecyclerView.ViewHolder {
        TextView brand, desc, badge;
        ConditionVH(View v) {
            super(v);
            brand = v.findViewById(R.id.tv_brand);
            desc = v.findViewById(R.id.tv_subtitle);
            badge = v.findViewById(R.id.tv_quota);
            badge.setText("策略");
            badge.setTextColor(0xFFE65100);
            badge.setBackgroundColor(0xFFFFF3E0);
        }
    }

    static class HeaderVH extends RecyclerView.ViewHolder {
        TextView tv, count;
        HeaderVH(View v) {
            super(v);
            tv = v.findViewById(R.id.tv_header);
            count = v.findViewById(R.id.tv_header_count);
        }
    }
}
