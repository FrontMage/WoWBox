#define COBJMACROS
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <initguid.h>
#include <dwrite_2.h>
#include <stdio.h>
#include <stdint.h>

typedef struct { IDWriteTextAnalysisSource iface; const WCHAR *locale; } TextSource;
static const WCHAR text[] = {0x6218,0x7f51,0x9b54,0x517d,0};
static HRESULT STDMETHODCALLTYPE src_qi(IDWriteTextAnalysisSource *p, REFIID id, void **out) {
    if(IsEqualIID(id,&IID_IUnknown)||IsEqualIID(id,&IID_IDWriteTextAnalysisSource)) { *out=p; return S_OK; }
    *out=NULL; return E_NOINTERFACE;
}
static ULONG STDMETHODCALLTYPE src_addref(IDWriteTextAnalysisSource *p) { (void)p; return 2; }
static ULONG STDMETHODCALLTYPE src_release(IDWriteTextAnalysisSource *p) { (void)p; return 1; }
static HRESULT STDMETHODCALLTYPE src_at(IDWriteTextAnalysisSource *p, UINT32 pos, const WCHAR **out, UINT32 *len) {
    (void)p; *out=pos<4 ? text+pos : NULL; *len=pos<4 ? 4-pos : 0; return S_OK;
}
static HRESULT STDMETHODCALLTYPE src_before(IDWriteTextAnalysisSource *p, UINT32 pos, const WCHAR **out, UINT32 *len) {
    (void)p; if (pos>4) pos=4; *out=pos ? text : NULL; *len=pos; return S_OK;
}
static DWRITE_READING_DIRECTION STDMETHODCALLTYPE src_direction(IDWriteTextAnalysisSource *p) {
    (void)p; return DWRITE_READING_DIRECTION_LEFT_TO_RIGHT;
}
static HRESULT STDMETHODCALLTYPE src_locale(IDWriteTextAnalysisSource *p, UINT32 pos, UINT32 *len, const WCHAR **locale) {
    TextSource *s=(TextSource*)p; *len=pos<4 ? 4-pos : 0; *locale=s->locale; return S_OK;
}
static HRESULT STDMETHODCALLTYPE src_number(IDWriteTextAnalysisSource *p, UINT32 pos, UINT32 *len, IDWriteNumberSubstitution **s) {
    (void)p; *len=pos<4 ? 4-pos : 0; *s=NULL; return S_OK;
}
static IDWriteTextAnalysisSourceVtbl source_vtbl = {src_qi,src_addref,src_release,src_at,src_before,src_direction,src_locale,src_number};
static const char *u8(const WCHAR *s) {
    static char b[4][512]; static unsigned i; char *p=b[(i++)%4];
    if (!s) return "(null)";
    if (!WideCharToMultiByte(CP_UTF8,0,s,-1,p,512,NULL,NULL)) return "(utf8-error)";
    return p;
}
static void run_map(FILE *out, IDWriteFontFallback *fb, const WCHAR *locale, const WCHAR *base) {
    TextSource source={{&source_vtbl},locale}; UINT32 len=0, cp[4]={0x6218,0x7f51,0x9b54,0x517d}; UINT16 glyph[4]={0}; FLOAT scale=0; IDWriteFont *font=NULL; IDWriteFontFamily *fam=NULL; IDWriteLocalizedStrings *names=NULL; IDWriteFontFace *face=NULL; WCHAR name[256]=L""; HRESULT h,gh=E_FAIL;
    h=IDWriteFontFallback_MapCharacters(fb,&source.iface,0,4,NULL,base,DWRITE_FONT_WEIGHT_NORMAL,DWRITE_FONT_STYLE_NORMAL,DWRITE_FONT_STRETCH_NORMAL,&len,&font,&scale);
    if(font) {
        if(SUCCEEDED(IDWriteFont_GetFontFamily(font,&fam))) {
            if(SUCCEEDED(IDWriteFontFamily_GetFamilyNames(fam,&names))) { IDWriteLocalizedStrings_GetString(names,0,name,256); IDWriteLocalizedStrings_Release(names); }
            IDWriteFontFamily_Release(fam);
        }
        if(SUCCEEDED(IDWriteFont_CreateFontFace(font,&face))) { gh=IDWriteFontFace_GetGlyphIndices(face,cp,4,glyph); IDWriteFontFace_Release(face); }
        IDWriteFont_Release(font);
    }
    fprintf(out,"map locale=%s base=%s hr=%08lx mapped=%u font=%s glyph_hr=%08lx glyphs=%u,%u,%u,%u scale=%.2f\n", u8(locale),u8(base),(unsigned long)h,len,*name?u8(name):"(none)",(unsigned long)gh,glyph[0],glyph[1],glyph[2],glyph[3],scale);
}
int wmain(void) {
    static const WCHAR *const families[]={L"MS Gothic",L"Microsoft YaHei",L"Source Han Sans CN",L"Noto Sans CJK SC",L"Noto Sans CJK TC",L"Noto Sans",L"Arial",L"Tahoma"};
    static const WCHAR *const locales[]={L"zh-Hans",L"zh-CN",L"zh-Hant",L"zh-TW",L"zh-HK",L"en-US",L""};
    static const WCHAR *const bases[]={NULL,L"Arial",L"Noto Sans",L"Microsoft YaHei"};
    WCHAR path[MAX_PATH],*end; FILE *out; IDWriteFactory2 *factory=NULL; IDWriteFontCollection *col=NULL; IDWriteFontFallback *fallback=NULL; HRESULT h; unsigned i,j;
    GetModuleFileNameW(NULL,path,MAX_PATH); end=path+lstrlenW(path); while(end>path && end[-1]!=L'\\' && end[-1]!=L'/') --end; lstrcpyW(end,L"font-fallback-probe.txt"); out=_wfopen(path,L"wb"); if(!out) return 2;
    h=DWriteCreateFactory(DWRITE_FACTORY_TYPE_SHARED,&IID_IDWriteFactory2,(IUnknown**)&factory); fprintf(out,"factory2 hr=%08lx\n",(unsigned long)h); if(FAILED(h)) {fclose(out); return 3;}
    h=IDWriteFactory2_GetSystemFontCollection(factory,&col,FALSE); fprintf(out,"collection hr=%08lx count=%u\n",(unsigned long)h,col?IDWriteFontCollection_GetFontFamilyCount(col):0);
    if(col) for(i=0;i<sizeof(families)/sizeof(families[0]);i++){UINT32 idx=~0u; BOOL exists=FALSE; h=IDWriteFontCollection_FindFamilyName(col,families[i],&idx,&exists);fprintf(out,"family name=%s hr=%08lx exists=%d index=%u\n",u8(families[i]),(unsigned long)h,exists,idx);}
    h=IDWriteFactory2_GetSystemFontFallback(factory,&fallback); fprintf(out,"fallback hr=%08lx available=%d\n",(unsigned long)h,fallback!=NULL);
    if(fallback) {for(i=0;i<sizeof(locales)/sizeof(locales[0]);i++)for(j=0;j<sizeof(bases)/sizeof(bases[0]);j++)run_map(out,fallback,locales[i],bases[j]);IDWriteFontFallback_Release(fallback);}
    if (col) IDWriteFontCollection_Release(col);
    IDWriteFactory2_Release(factory);
    fclose(out);
    return 0;
}
