package com.sunwin.deepseekharness;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.*;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.core.content.FileProvider;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import static com.sunwin.deepseekharness.Appearance.*;

public final class MainActivity extends Activity {
    private static final int PICK=20, CAMERA=21, EXPORT=22, DOCUMENT=23, NOTIFICATIONS=24;
    private HarnessApp app;
    private Appearance a;
    private final ExecutorService files=Executors.newSingleThreadExecutor();
    private final ExecutorService thumbnails=Executors.newSingleThreadExecutor();
    private final android.util.LruCache<String,Bitmap> thumbs=new android.util.LruCache<String,Bitmap>(4*1024*1024) {
        @Override protected int sizeOf(String key,Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };
    private List<Attachment> draftFiles=Collections.emptyList();
    private List<Long> shownDraft;
    private HorizontalScrollView attachmentScroll;
    private long chatId, photoChat;
    private String screen="chat", draft="", cameraPath;
    private boolean openLogin, visible;
    private LinearLayout root, content, thread, intro, attachment;
    private ScrollView scroll;
    private TextView header, subtitle, meter;
    private EditText input;
    private ImageButton send;
    private final HashMap<Long,TextView> bubbles=new HashMap<>();
    private String introKey="";
    private android.content.SharedPreferences preferences() { return getSharedPreferences("ui",MODE_PRIVATE); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        app=(HarnessApp)getApplication(); a=new Appearance(this);
        chatId=preferences().getLong("chat",0);
        if (app.history.chat(chatId)==null) {
            List<History.Chat> items=app.history.list();
            choose(items.isEmpty()?app.history.create():items.get(0).id);
        }
        if (state!=null) {
            screen=state.getString("screen","chat"); draft=state.getString("draft","");
            cameraPath=state.getString("camera"); photoChat=state.getLong("photoChat");
            openLogin=state.getBoolean("launch");
        }
        startRuntime(); draw();
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        // Window focus returns after the notification dialog. Never stack two
        // system prompts or repeat either one after refusal/activity recreation.
        if (!focused || !visible || isFinishing()) return;
        if (Build.VERSION.SDK_INT>=33 && !preferences().getBoolean("notificationAsked",false)
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
            preferences().edit().putBoolean("notificationAsked",true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATIONS);
        } else if (!preferences().getBoolean("batteryAsked",false)
                && !getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName())) {
            preferences().edit().putBoolean("batteryAsked",true).apply();
            Intent request=new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:"+getPackageName()));
            try { startActivity(request); } catch (ActivityNotFoundException|SecurityException ignored) { }
        }
    }
    private void startRuntime() { startForegroundService(new Intent(this,HarnessService.class)); }
    @Override public void onResume() {
        super.onResume(); visible=true; app.listen(this::update); app.account.refresh(false); update();
    }
    @Override public void onPause() { visible=false; app.listen(null); super.onPause(); }
    @Override public void onDestroy() { files.shutdown(); thumbnails.shutdownNow(); super.onDestroy(); }
    @Override public void onSaveInstanceState(Bundle b) {
        super.onSaveInstanceState(b);
        b.putString("screen",screen); b.putString("draft",draft);
        b.putString("camera",cameraPath); b.putLong("photoChat",photoChat); b.putBoolean("launch",openLogin);
    }
    @Override public void onBackPressed() {
        if (!"chat".equals(screen)) go("chat"); else super.onBackPressed();
    }
    private void choose(long id) { chatId=id; preferences().edit().putLong("chat",id).apply(); }
    private void selectChat(long id) {
        if (id!=chatId) { draft=""; choose(id); }
        go("chat");
    }
    private void go(String page) {
        if (input!=null) getSystemService(InputMethodManager.class).hideSoftInputFromWindow(input.getWindowToken(),0);
        screen=page; draw();
    }
    private void draw() {
        input=null; send=null; introKey=""; bubbles.clear();
        root=a.column(); root.setBackgroundColor(PAPER); root.setFocusableInTouchMode(true);
        setContentView(root);
        boolean chat="chat".equals(screen);
        LinearLayout bar=a.row(); a.pad(bar,8,4); root.addView(bar,a.size(-1,62));
        bar.addView(a.icon(chat?R.drawable.ic_history:R.drawable.ic_back,chat?"历史对话":"返回",()->go(chat?"history":"chat")),a.size(48,48));
        LinearLayout titles=a.column(); titles.setGravity(Gravity.CENTER_VERTICAL); a.pad(titles,6,0);
        header=a.text("",18,INK,true); header.setSingleLine(true); header.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(header); subtitle=a.text("",12,DIM,false); titles.addView(subtitle);
        bar.addView(titles,new LinearLayout.LayoutParams(0,-1,1));
        if (chat) {
            meter=a.text("",12,DIM,false); meter.setGravity(Gravity.CENTER);
            a.buttonStyle(meter,PAPER,14); meter.setTooltipText("压缩上下文");
            meter.setOnClickListener(v->{
                PopupMenu menu=new PopupMenu(this,v);
                menu.getMenu().add("压缩上下文").setOnMenuItemClickListener(item->{ app.chat.compact(chatId); return true; });
                menu.show();
            });
            bar.addView(meter,a.size(48,48));
            bar.addView(a.icon(R.drawable.ic_settings,"设置",()->go("settings")),a.size(48,48));
        }
        if ("history".equals(screen)) bar.addView(a.icon(R.drawable.ic_plus,"新建对话",()->selectChat(app.history.create())),a.size(48,48));
        View rule=new View(this); rule.setBackgroundColor(LINE); root.addView(rule,new LinearLayout.LayoutParams(-1,1));
        content=a.column(); root.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        if (chat) buildChat();
        else if ("history".equals(screen)) showHistory();
        update();
    }
    private void update() {
        if (root==null || isFinishing()) return;
        if (openLogin && app.account.authorize!=null && visible && app.account.pending()) {
            openLogin=false; launchAuthorization(false);
        } else if (!app.account.loading && !app.account.pending() && !"".equals(app.account.phase)) openLogin=false;
        subtitle.setVisibility(View.GONE);
        switch (screen) {
            case "history": header.setText("历史对话"); break;
            case "settings": header.setText("设置"); showSettings(); break;
            default:
                History.Chat chat=app.history.chat(chatId); header.setText(chat.title);
                String percent=app.history.meter(chatId,app.account.selected).percent();
                meter.setText(percent); meter.setContentDescription("上下文用量 "+percent);
                Loopback api=app.api;
                subtitle.setText(api!=null && app.account.stored ? app.account.modelStatus(api) : status());
                subtitle.setVisibility(View.VISIBLE);
                renderMessages(); drawAttachment(); updateSend();
        }
    }
    private String status() {
        switch (app.serviceState) {
            case "starting": return "正在启动";
            case "failed": return app.serviceFailure.isEmpty()?"服务启动失败":app.serviceFailure;
            case "stopped": return "服务已停止";
            default: return app.account.label();
        }
    }
    private void buildChat() {
        scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setVerticalScrollBarEnabled(false);
        content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout canvas=a.column(); a.pad(canvas,20,14); scroll.addView(canvas);
        intro=a.column(); intro.setGravity(Gravity.CENTER); canvas.addView(intro,new LinearLayout.LayoutParams(-1,0,1));
        thread=a.column(); canvas.addView(thread,a.size(-1,-2));
        LinearLayout composer=a.column(); a.pad(composer,8,6); composer.setBackground(a.shape(WHITE,22,LINE));
        LinearLayout.LayoutParams box=a.size(-1,-2); box.setMargins(a.dp(14),a.dp(4),a.dp(14),a.dp(12)); content.addView(composer,box);
        attachmentScroll=new HorizontalScrollView(this); attachmentScroll.setHorizontalScrollBarEnabled(false);
        composer.addView(attachmentScroll,a.size(-1,-2));
        attachment=a.row(); attachmentScroll.addView(attachment); shownDraft=null;
        input=new EditText(this); input.setTextSize(17); input.setTextColor(INK); input.setHintTextColor(DIM);
        input.setHint("输入消息"); input.setBackgroundColor(WHITE); a.pad(input,10,7);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMaxLines(5); input.setMinHeight(a.dp(48)); input.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);
        input.setText(draft); input.setSelection(input.length()); composer.addView(input,a.size(-1,-2));
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int st,int count,int after) { }
            public void onTextChanged(CharSequence s,int st,int before,int count) { draft=s.toString(); updateSend(); }
            public void afterTextChanged(Editable e) { }
        });
        LinearLayout tools=a.row(); composer.addView(tools);
        ImageButton gallery=a.icon(R.drawable.ic_gallery,"相册",this::gallery), camera=a.icon(R.drawable.ic_camera,"拍照",this::camera);
        for (ImageButton b:new ImageButton[]{gallery,camera}) { a.buttonStyle(b,WHITE,12); tools.addView(b,a.size(48,48)); }
        TextView file=a.button("文件",false,this::documents);
        a.buttonStyle(file,WHITE,12); a.pad(file,6,0); tools.addView(file,a.size(48,48));
        tools.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        send=a.icon(R.drawable.ic_send,"发送",this::send); a.buttonStyle(send,BLUE,15); send.setColorFilter(WHITE);
        tools.addView(send,a.size(48,46));
    }
    private void renderMessages() {
        List<History.Message> rows=app.history.messages(chatId);
        intro.setVisibility(rows.isEmpty()?View.VISIBLE:View.GONE);
        if (rows.isEmpty()) {
            String key=status()+"|"+app.account.stored;
            if (!key.equals(introKey)) {
                introKey=key; intro.removeAllViews();
                ImageView mark=new ImageView(this); mark.setImageResource(R.drawable.app_icon); intro.addView(mark,a.size(58,58));
                a.gap(intro,16); TextView title=a.text("DeepSeek 助手",27,INK,true);
                title.setGravity(Gravity.CENTER); intro.addView(title);
                if (!app.account.stored || app.api==null) {
                    a.gap(intro,20);
                    boolean retry=retryService();
                    TextView b=a.button(retry?"重新启动":app.account.pending()?"继续登录":"登录 DeepSeek",false,
                            ()->{ if (retry) startRuntime(); else login(); });
                    b.setEnabled(retry || app.api!=null); b.setAlpha(b.isEnabled()?1f:.5f); intro.addView(b,a.size(-2,44));
                }
            }
            return;
        }
        boolean bottom=scroll.getScrollY()+scroll.getHeight()>=thread.getHeight()-a.dp(64);
        for (History.Message m:rows) {
            TextView body=bubbles.get(m.id);
            if (body==null) {
                boolean user="user".equals(m.role);
                LinearLayout group=a.column(); group.setGravity(user?Gravity.END:Gravity.START);
                LinearLayout.LayoutParams gp=a.size(-1,-2); gp.bottomMargin=a.dp(16);
                if (user) gp.leftMargin=a.dp(34); else gp.rightMargin=a.dp(18); thread.addView(group,gp);
                if (!user) { group.addView(a.text("DeepSeek",12,DIM,true)); a.gap(group,5); }
                if (!m.attachments.isEmpty()) messageFiles(group,m.attachments);
                body=a.text("",16,INK,false); body.setTextIsSelectable(true);
                if (user) { body.setBackground(a.shape(PALE,17,0)); a.pad(body,14,11); }
                else a.pad(body,0,3);
                group.addView(body,a.size(-2,-2)); bubbles.put(m.id,body);
            }
            String text=m.text;
            if ("pending".equals(m.state) && text.isEmpty()) text="正在回复…";
            if ("interrupted".equals(m.state)) {
                String reason=TextUtils.isEmpty(m.error)?"回复已中断":m.error;
                text=text.isEmpty()?reason:text+"\n（"+reason+"）";
            }
            body.setVisibility(text.isEmpty()?View.GONE:View.VISIBLE);
            if (!TextUtils.equals(text,body.getText())) body.setText(text);
        }
        if (bottom) scroll.post(()->scroll.scrollTo(0,thread.getBottom()));
    }
    private void updateSend() {
        if (send==null) return;
        boolean can=!app.chat.busy() && !app.imports.busy(chatId) && (!draft.trim().isEmpty() || !draftFiles.isEmpty());
        send.setEnabled(can); send.setAlpha(can?1f:.44f);
    }
    private void send() {
        if (app.api==null) { toast(status()); if (retryService()) startRuntime(); return; }
        if (!app.account.stored) { login(); return; }
        if (app.account.selected.isEmpty()) {
            toast(app.account.modelError.isEmpty() ? "正在获取可用模型" : app.account.modelError);
            app.account.refresh(true); return;
        }
        if (app.chat.send(chatId,draft.trim(),app.history.draft(chatId))) { input.setText(""); update(); }
    }
    private boolean retryService() { return "failed".equals(app.serviceState)||"stopped".equals(app.serviceState); }

    private LinearLayout page() {
        content.removeAllViews();
        ScrollView s=new ScrollView(this); content.addView(s,a.size(-1,-1));
        LinearLayout page=a.column(); a.pad(page,22,22); s.addView(page); return page;
    }
    private void groupName(LinearLayout page,String name) { page.addView(a.text(name,13,DIM,true)); a.gap(page,9); }
    private LinearLayout card(LinearLayout page) {
        LinearLayout card=a.column(); card.setBackground(a.shape(WHITE,18,LINE)); a.pad(card,4,3); page.addView(card,a.size(-1,-2)); return card;
    }
    private View row(LinearLayout card,String left,String right,Runnable action) {
        LinearLayout line=a.row(); a.pad(line,14,11);
        TextView text=a.text(left,16,INK,false); line.addView(text,new LinearLayout.LayoutParams(0,-2,1));
        if (!right.isEmpty()) line.addView(a.text(right,14,DIM,false));
        if (action!=null) {
            ImageView next=new ImageView(this); next.setImageResource(R.drawable.ic_chevron); next.setColorFilter(DIM);
            line.addView(next,a.size(21,21)); a.buttonStyle(line,WHITE,13); line.setOnClickListener(v->action.run());
        }
        line.setMinimumHeight(a.dp(52)); card.addView(line,a.size(-1,-2)); return line;
    }
    private void showHistory() {
        LinearLayout page=page(), list=card(page);
        for (History.Chat chat:app.history.list()) {
            View line=row(list,chat.title,chat.id==chatId?"当前":"",()->selectChat(chat.id));
            line.setOnLongClickListener(v->{
                // A handled long click gives one system haptic before the posted dialog opens.
                v.post(()->confirmDelete(chat.id));
                return true;
            });
        }
    }
    private void confirmDelete(long id) {
        if (isFinishing() || isDestroyed() || !"history".equals(screen)) return;
        new AlertDialog.Builder(this).setTitle("删除对话").setNegativeButton("取消",null)
            .setPositiveButton("删除",(dialog,which)->{
                try {
                    long next=app.chat.delete(id,chatId);
                    if (next!=chatId) selectChat(next); else showHistory();
                } catch (android.database.sqlite.SQLiteException e) {
                    app.log.add(Diagnostics.Event.CHAT_DELETE_FAILED); toast("删除失败");
                }
            }).show();
    }
    private void showSettings() {
        LinearLayout page=page(); groupName(page,"DeepSeek 账号"); LinearLayout account=card(page);
        row(account,"登录状态",status(),null);
        if (retryService()) row(account,"重新启动","",this::startRuntime);
        if (app.api!=null) {
            if (app.account.stored) row(account,"退出登录","",()->new AlertDialog.Builder(this).setMessage("退出当前账号？")
                    .setNegativeButton("取消",null).setPositiveButton("退出",(d,w)->app.account.signOut()).show());
            else if (app.account.pending()) {
                if (app.account.authorize!=null) {
                    row(account,"继续登录","",()->launchAuthorization(false));
                    row(account,"备用登录","",()->launchAuthorization(true));
                }
                row(account,"取消登录","",()->{openLogin=false; app.account.cancel();});
            } else row(account,"登录 DeepSeek","",this::login);
        }
        if (!app.account.balance.isEmpty()) row(account,"余额",app.account.balance,null);
        row(account,"充值","",()->openExternal(Uri.parse("https://platform.deepseek.com/top_up")));
        Loopback api=app.api;
        if (api!=null && app.account.stored && !app.account.modelError.isEmpty())
            row(account,"模型",app.account.modelError,()->app.account.refresh(true));
        if (api!=null && !app.account.models.isEmpty()) {
            a.gap(page,24); groupName(page,api.listing.label); LinearLayout models=card(page);
            for (String name:app.account.models) row(models,name,name.equals(app.account.selected)?"已选择":"",
                    ()->{ app.account.selected=name; app.changed(); });
        }
        a.gap(page,28); LinearLayout other=card(page); row(other,"导出诊断日志","",this::exportLog);
    }
    private void login() {
        if (app.account.pending() && app.account.authorize!=null) { launchAuthorization(false); return; }
        openLogin=true; app.account.signIn();
    }
    private void launchAuthorization(boolean tab) {
        java.net.URI url=app.account.authorize;
        if (url==null) return;
        Uri u=Uri.parse(url.toASCIIString());
        if (!tab) {
            try { startActivity(new Intent(Intent.ACTION_VIEW,u).addCategory(Intent.CATEGORY_BROWSABLE)); return; }
            catch (ActivityNotFoundException ignored) { }
        }
        try {
            CustomTabsIntent launch=new CustomTabsIntent.Builder().setShowTitle(false).build();
            String browser=androidx.browser.customtabs.CustomTabsClient.getPackageName(this,null);
            if (browser!=null) launch.intent.setPackage(browser);
            launch.launchUrl(this,u);
        } catch (RuntimeException e) { toast("登录未完成，请重试"); }
    }
    private void openExternal(Uri uri) {
        try { startActivity(new Intent(Intent.ACTION_VIEW,uri)); } catch (ActivityNotFoundException e) { toast("未找到浏览器"); }
    }
    private ImageView preview(String path,String label) {
        ImageView img=new ImageView(this) {
            private final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
            private final RectF bounds=new RectF();
            private boolean loading,failed;
            @Override protected void onDraw(Canvas canvas) {
                // The history uses a ScrollView. Off-screen bubbles must not pin every decoded bitmap.
                Bitmap bitmap=thumbs.get(path);
                if (bitmap!=null) {
                    float scale=Math.max((float)getWidth()/bitmap.getWidth(),(float)getHeight()/bitmap.getHeight());
                    float w=bitmap.getWidth()*scale, h=bitmap.getHeight()*scale;
                    bounds.set((getWidth()-w)/2,(getHeight()-h)/2,(getWidth()+w)/2,(getHeight()+h)/2);
                    canvas.drawBitmap(bitmap,null,bounds,paint);
                } else if (failed) super.onDraw(canvas);
                else if (!loading && !thumbnails.isShutdown()) {
                    loading=true;
                    int edge=Math.min(440,a.dp(148));
                    thumbnails.execute(()->{
                        try {
                            Bitmap decoded=Photos.load(MainActivity.this,path,edge);
                            thumbs.put(path,decoded); post(()->{ loading=false; invalidate(); });
                        } catch (IOException e) {
                            post(()->{ loading=false; failed=true; setImageResource(R.drawable.ic_gallery); });
                        }
                    });
                }
            }
        };
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackground(a.shape(PALE,12,0)); img.setClipToOutline(true); img.setContentDescription(label);
        return img;
    }
    private List<String> imagePaths(List<Attachment> items) {
        List<String> paths=new ArrayList<>();
        for (Attachment item:items) paths.addAll(item.imagePaths());
        return paths;
    }
    private void messageFiles(LinearLayout group,List<Attachment> items) {
        List<String> paths=imagePaths(items);
        int width=getResources().getDisplayMetrics().widthPixels;
        int side=Math.min(96,Math.max(48,(int)((width-a.dp(88))/getResources().getDisplayMetrics().density/3)));
        LinearLayout row=null;
        for (int i=0;i<paths.size();i++) {
            if (i%3==0) { row=a.row(); group.addView(row,a.size(-2,-2)); }
            final int index=i;
            ImageView image=preview(paths.get(i),"照片 "+(i+1));
            image.setOnClickListener(v->PhotoViewer.open(this,paths,index));
            LinearLayout.LayoutParams size=paths.size()==1 ? a.size(148,116) : a.size(side,side);
            size.setMargins(a.dp(2),a.dp(2),a.dp(2),a.dp(2)); row.addView(image,size);
        }
        for (Attachment item:items) if (item.kind!=Attachment.Kind.PHOTO) group.addView(fileChip(item),a.size(-2,42));
        a.gap(group,4);
    }
    private TextView fileChip(Attachment item) {
        TextView chip=a.button(item.name,false,()->openDocument(item));
        chip.setSingleLine(); chip.setMaxWidth(a.dp(245)); chip.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        return chip;
    }
    private void openDocument(Attachment item) {
        if (item.kind==Attachment.Kind.SCAN) { PhotoViewer.open(this,item.paths,0); return; }
        files.execute(()->{
            try {
                String value=new String(Files.readAllBytes(Attachment.file(this,item.paths.get(0)).toPath()),StandardCharsets.UTF_8);
                runOnUiThread(()->{
                    if (isFinishing() || isDestroyed()) return;
                    TextView text=a.text(value,15,INK,false); a.pad(text,20,12); text.setTextIsSelectable(true);
                    if (!item.imageOffsets.isEmpty()) {
                        SpannableString body=new SpannableString(value);
                        for (int i=0;i<item.imageOffsets.size();i++) {
                            int at=item.imageOffsets.get(i), index=i;
                            String mark=Attachment.marker(i+1);
                            if (value.startsWith(mark,at)) body.setSpan(new ClickableSpan() {
                                @Override public void onClick(View v) { PhotoViewer.open(MainActivity.this,item.imagePaths(),index); }
                            },at,at+mark.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        text.setText(body); text.setMovementMethod(LinkMovementMethod.getInstance());
                    }
                    ScrollView view=new ScrollView(this); view.addView(text);
                    new AlertDialog.Builder(this).setTitle(item.name).setView(view).setPositiveButton("关闭",null).show();
                });
            } catch (IOException e) { app.notice("无法读取该文件"); }
        });
    }
    private void drawAttachment() {
        draftFiles=app.history.draft(chatId);
        List<Long> ids=new ArrayList<>();
        for (Attachment item:draftFiles) ids.add(item.id);
        if (ids.equals(shownDraft)) return;
        shownDraft=ids;
        attachment.removeAllViews(); attachmentScroll.setVisibility(ids.isEmpty()?View.GONE:View.VISIBLE);
        List<String> paths=imagePaths(draftFiles);
        for (Attachment item:draftFiles) {
            if (item.kind==Attachment.Kind.PHOTO) {
                String path=item.paths.get(0);
                ImageView img=preview(path,"待发照片 "+(paths.indexOf(path)+1));
                img.setOnClickListener(v->PhotoViewer.open(this,paths,paths.indexOf(path)));
                attachment.addView(img,a.size(62,62));
            } else attachment.addView(fileChip(item),a.size(-2,42));
            attachment.addView(a.icon(R.drawable.ic_close,item.kind==Attachment.Kind.PHOTO ? "移除照片" : "移除文件",()->{
                app.history.removeDraft(chatId,item.id,app.log); drawAttachment(); updateSend();
            }),a.size(44,44));
        }
    }
    private void gallery() {
        if (app.imports.busy(chatId)) return;
        int slots=Attachment.MAX_PHOTOS-Attachment.photos(draftFiles);
        if (slots<=0) { toast("最多 9 张图片"); return; }
        photoChat=chatId;
        if (Build.VERSION.SDK_INT>=33) {
            Intent pick=new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*");
            if (slots>1) pick.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX,Math.min(slots,MediaStore.getPickImagesMaxLimit()));
            try { startActivityForResult(pick,PICK); return; } catch (ActivityNotFoundException ignored) {}
        }
        try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE,slots>1),PICK); }
        catch (ActivityNotFoundException e) { toast("相册不可用"); }
    }
    private void documents() {
        if (app.imports.busy(chatId)) return;
        if (Attachment.documents(draftFiles)>=Attachment.MAX_DOCUMENTS) { toast("最多 4 个文件"); return; }
        photoChat=chatId;
        try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true),DOCUMENT); }
        catch (ActivityNotFoundException e) { toast("无法选择文件"); }
    }
    private void camera() {
        if (app.imports.busy(chatId)) return;
        if (Attachment.photos(draftFiles)>=Attachment.MAX_PHOTOS) { toast("最多 9 张图片"); return; }
        photoChat=chatId;
        try {
            File dir=new File(getCacheDir(),"camera"); dir.mkdirs(); File f=File.createTempFile("photo-",".jpg",dir); cameraPath=f.getPath();
            Uri dest=FileProvider.getUriForFile(this,getPackageName()+".photos",f);
            Intent take=new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT,dest);
            take.setClipData(ClipData.newRawUri("照片",dest));
            take.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(take,CAMERA);
        } catch (IOException|RuntimeException e) { clearCamera(); toast("相机不可用"); }
    }
    private void clearCamera() { if (cameraPath!=null) { new File(cameraPath).delete(); cameraPath=null; } }
    private void exportLog() {
        try { startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain").putExtra(Intent.EXTRA_TITLE,"DeepSeek-诊断日志.txt"),EXPORT); }
        catch (ActivityNotFoundException e) { toast("无法选择保存位置"); }
    }
    @Override protected void onActivityResult(int req,int result,Intent data) {
        super.onActivityResult(req,result,data);
        if (result!=RESULT_OK) { if (req==CAMERA) clearCamera(); return; }
        Uri uri=data==null?null:data.getData();
        if (req==EXPORT && uri!=null) {
            files.execute(()->{ try (OutputStream out=getContentResolver().openOutputStream(uri,"wt")) {
                    if (out==null) throw new IOException(); app.log.export(out); app.notice("日志已导出");
                } catch (Exception e) { app.notice("导出失败，请重试"); } }); return;
        }
        if (req!=PICK && req!=CAMERA && req!=DOCUMENT) return;
        File camera=req==CAMERA && cameraPath!=null ? new File(cameraPath) : null;
        cameraPath=null;
        if (photoChat!=chatId) { if (camera!=null) camera.delete(); return; }
        Set<Uri> sources=new LinkedHashSet<>();
        if (camera!=null) sources.add(Uri.fromFile(camera));
        else if (data!=null && data.getClipData()!=null) {
            ClipData clip=data.getClipData();
            int cap=req==DOCUMENT ? Attachment.MAX_DOCUMENTS : Attachment.MAX_PHOTOS;
            for (int i=0;i<Math.min(clip.getItemCount(),cap+1);i++) {
                Uri picked=clip.getItemAt(i).getUri();
                if (picked!=null) sources.add(picked);
            }
        } else if (uri!=null) sources.add(uri);
        if (!sources.isEmpty()) app.imports.add(photoChat,new ArrayList<>(sources),req==DOCUMENT,camera);
    }
    private void toast(String message) { app.notice(message); }
}
