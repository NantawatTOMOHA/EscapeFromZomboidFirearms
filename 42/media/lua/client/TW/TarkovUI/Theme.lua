-- Escape from Tarkov look of the weapon screens (modding, builds, inspect). Colours are measured on
-- the user's reference screenshots (EFT 0.16 / SPT 3.11.4, Lightshot "SPT MENU", 20.10.2026); sizes
-- are the reference's at 3840x2160 and scale with the screen height, as Tarkov's UI does. Text uses
-- the game's SDF fonts (Open Sans), which stay sharp at any size. Drawing only; no state.
local Theme={}

local function rgb(r,g,b) return {r=r/255,g=g/255,b=b/255} end
Theme.WHITE=rgb(236,238,236)
Theme.TEXT=rgb(220,222,218)
Theme.MUTED=rgb(150,154,154)
Theme.DIM=rgb(96,100,102)
Theme.BLACK=rgb(0,0,0)
-- Slot cells over the frosted background; their light frame.
Theme.CELL=rgb(4,8,10)
Theme.CELL_ALPHA=0.62
Theme.CELL_EMPTY=rgb(18,19,20)
Theme.CELL_BORDER=rgb(76,83,86)
Theme.CELL_HOVER=rgb(196,202,204)
-- Builds: parts available for the build get a blue frame and tint (reference: Screenshot_328).
Theme.OWNED_BORDER=rgb(78,124,160)
Theme.OWNED_TINT=rgb(30,62,88)
Theme.MISSING_BORDER=rgb(150,52,40)
Theme.MISSING_TINT=rgb(70,18,14)
Theme.BAG_BORDER=rgb(170,128,48)
-- Part grid (the slot's drop-down): dark navy cells, olive cells for parts the player has.
Theme.GRID_CELL=rgb(8,20,26)
Theme.GRID_BORDER=rgb(73,81,84)
Theme.GRID_OWNED=rgb(107,114,16)
Theme.GRID_STRIP=rgb(4,6,7)
Theme.GRID_REFUSED=rgb(96,14,12)
-- Leader lines and mount points.
Theme.LINE=rgb(200,205,208)
Theme.LINE_ALPHA=0.38
Theme.POINT=rgb(240,242,242)
Theme.VITAL=rgb(47,118,186)
Theme.VITAL_DARK=rgb(10,38,72)
Theme.AMMO=rgb(120,200,90)
-- Stats rows (INFO panel, inspect window).
Theme.ROW=rgb(0,0,0)
Theme.ROW_ALPHA=0.78
Theme.ROW_BORDER=rgb(86,90,95)
Theme.BAR_TOP=rgb(87,90,94)
Theme.BAR_BOTTOM=rgb(65,68,72)
Theme.HATCH_LIGHT=rgb(52,22,22)
Theme.HATCH_DARK=rgb(26,10,10)
Theme.BETTER=rgb(60,123,148)
Theme.WORSE=rgb(150,40,32)
-- Builds menu, notice and buttons.
Theme.BUTTON=rgb(37,40,41)
Theme.BUTTON_HOVER=rgb(62,66,68)
Theme.BUTTON_DISABLED=rgb(21,22,22)
Theme.BUTTON_DANGER=rgb(54,6,6)
Theme.BUTTON_DANGER_ICON=rgb(214,24,24)
Theme.ASSEMBLE=rgb(26,32,4)
Theme.ASSEMBLE_READY=rgb(64,84,16)
Theme.NOTICE_OK=rgb(132,191,30)
Theme.NOTICE_WARN=rgb(178,58,32)
Theme.NAME_FIELD=rgb(136,156,181)
Theme.HANDLE=rgb(152,158,161)
Theme.PROGRESS=rgb(132,191,30)
Theme.STRIPE_A=rgb(13,14,15)
Theme.STRIPE_B=rgb(20,21,22)

-- Scale of the reference (3840x2160) for this screen. A small screen with a large game font gets
-- the font's size instead (the game's small font is about 36 px where the reference is 1:1), so
-- text does not shrink below the player's own reading size; but never more than twice the
-- screen's own scale, past which a full screen of Tarkov's layout no longer fits (720p with the
-- 3x and 4x fonts).
function Theme.scale(height)
    height=height or getCore():getScreenHeight()
    local font=getTextManager():getFontHeight(UIFont.Small)
    return math.max(0.4,height/2160,math.min(font/36,height/1080))
end

-- Text: Tarkov's sizes are given in pixels of the font's em at 4K; the SDF fonts are drawn zoomed.
Theme.FONT=UIFont.SdfRegular
Theme.FONT_BOLD=UIFont.SdfBold
local EM=32
local function zoomOf(px) return math.max(0.2,px/EM) end
local function isSdf(font) return font==UIFont.SdfRegular or font==UIFont.SdfBold end
-- The SDF fonts hold Latin, Greek, Cyrillic and punctuation only (873 glyphs, up to U+25CA): TW/SdfGlyphs.lua
-- is generated from the game's media/fonts/sdfRegular.fnt. The game's AngelCodeFont draws a "?" for a code
-- above the font's last glyph and nothing at all for a code below it, so Chinese, Japanese and Korean names
-- came out as "?" and Thai ones vanished. The game's bitmap fonts are the ones a language replaces
-- (media/fonts/<LANG>/fonts.txt points Small/Medium/Large at the Chinese/Japanese/Korean/Thai font; the SDF
-- entries are the English files for every language): a string the SDF font cannot draw goes to the bitmap
-- font of the closest size instead, scaled so that its line height is the SDF one at the same px.
local Glyphs=require('TW/SdfGlyphs')
local Text=require('TW/Text')
local FALLBACK={UIFont.Small,UIFont.Medium,UIFont.Large}
-- A string is one character per element in Kahlua (UTF-16 code unit) and one to four bytes in plain Lua
-- (UTF-8, the offline tests): TW/Text reads both, this turns the character into its code point.
local function codepoint(char)
    local b=char:byte(1) or 0
    if #char==1 then return b end
    local c2,c3,c4=char:byte(2) or 0,char:byte(3) or 0,char:byte(4) or 0
    if #char==2 then return (b-0xC0)*0x40+(c2-0x80) end
    if #char==3 then return ((b-0xE0)*0x40+(c2-0x80))*0x40+(c3-0x80) end
    return (((b-0xF0)*0x40+(c2-0x80))*0x40+(c3-0x80))*0x40+(c4-0x80)
end
-- Runs of consecutive code points, binary search.
local function holds(ranges,c)
    local lo,hi=1,#ranges
    while lo<=hi do
        local mid=math.floor((lo+hi)/2)
        if c<ranges[mid][1] then hi=mid-1 elseif c>ranges[mid][2] then lo=mid+1 else return true end
    end
    return false
end
local function fontHolds(font,text)
    local ranges=font==Theme.FONT_BOLD and Glyphs.SdfBold or Glyphs.SdfRegular
    for _,char in ipairs(Text.chars(text)) do
        if not holds(ranges,codepoint(char)) then return false end
    end
    return true
end
-- Every label of a screen is measured and drawn on every frame: remember the answers (a build name typed
-- by the player is a new key, so the table is emptied when it grows past a few screens of them).
local covered={regular={},bold={}}
local coveredCount=0
local function sdfHolds(font,text)
    local bold=font==Theme.FONT_BOLD
    local bucket=bold and covered.bold or covered.regular
    local known=bucket[text]
    if known==nil then
        known=fontHolds(font,text)
        if coveredCount>=400 then covered={regular={},bold={}}; coveredCount=0 end
        bucket=bold and covered.bold or covered.regular
        bucket[text]=known
        coveredCount=coveredCount+1
    end
    return known
end
-- Can the font draw every character of the string?
function Theme.covers(value,font)
    font=font or Theme.FONT
    if not isSdf(font) then return true end
    return sdfHolds(font,tostring(value))
end
-- The font and zoom a string is drawn with: the requested SDF font, or the game's bitmap font whose own
-- size is closest to px (the one that has the characters, when one has them), scaled to the SDF line
-- height so that rows and boxes built from Theme.lineHeight stay where they are.
function Theme.face(value,px,font)
    font=font or Theme.FONT
    value=tostring(value)
    if not isSdf(font) or sdfHolds(font,value) then return font,zoomOf(px) end
    local tm=getTextManager()
    local want=tm:getFontHeight(font)*zoomOf(px)
    local best,bestFont,covering,coveringFont
    for _,f in ipairs(FALLBACK) do
        local h=tm:getFontHeight(f)
        if h>0 then
            local d=math.abs(h-px)
            if not best or d<best then best,bestFont=d,f end
            if not covering and fontHolds(f,value) then covering,coveringFont=true,f end
        end
    end
    local f=coveringFont or bestFont
    if not f then return font,zoomOf(px) end
    return f,want/tm:getFontHeight(f)
end
function Theme.textWidth(text,px,font)
    local f,z=Theme.face(text,px,font)
    return getTextManager():MeasureStringX(f,tostring(text))*z
end
function Theme.lineHeight(px,font)
    return getTextManager():getFontHeight(font or Theme.FONT)*zoomOf(px)
end
-- The Lua UI draws SDF fonts through the plain sprite shader (a soft glow); the Java part wraps
-- them in the game's SDF shader (TWZB.sdfBegin, threshold 0.125 / zoom, as AtomUIText).
local function sdfBegin(px,outline,a)
    local zb=rawget(_G,'TWZB')
    if Theme.noSdfShader or not (zb and zb.sdfBegin) then return false end
    local ok,on=pcall(zb.sdfBegin,zoomOf(px),outline or 0,0,0,0,a or 0.9,false)
    if not ok then Theme.noSdfShader=true; print('[TW] SDF text shader unavailable: '..tostring(on)); return false end
    return on==true
end
local function sdfEnd() TWZB.sdfEnd() end
-- y is the em's top.
function Theme.text(ui,value,x,y,px,color,alpha,font,align)
    value=tostring(value)
    local f,z=Theme.face(value,px,font)
    if align=='right' then x=x-getTextManager():MeasureStringX(f,value)*z
    elseif align=='centre' then x=x-getTextManager():MeasureStringX(f,value)*z/2 end
    local shaded=isSdf(f) and sdfBegin(px,0)
    ui:drawTextZoomed(value,x,y,z,color.r,color.g,color.b,alpha or 1,f)
    if shaded then sdfEnd() end
end
-- Text with a dark outline (labels straight over the model, as Tarkov's): the SDF shader's own
-- outline, else four dark copies.
function Theme.outlinedText(ui,value,x,y,px,color,alpha,font)
    font=font or Theme.FONT
    value=tostring(value)
    local f=Theme.face(value,px,font)
    if isSdf(f) and sdfBegin(px,0.32,0.9*(alpha or 1)) then
        ui:drawTextZoomed(value,x,y,zoomOf(px),color.r,color.g,color.b,alpha or 1,f)
        sdfEnd()
        return
    end
    local o=math.max(1,math.floor(px/14))
    for _,d in ipairs({{-o,0},{o,0},{0,-o},{0,o}}) do Theme.text(ui,value,x+d[1],y+d[2],px,Theme.BLACK,(alpha or 1)*0.85,font) end
    Theme.text(ui,value,x,y,px,color,alpha,font)
end
-- Shorten to a width with an ellipsis.
function Theme.fit(value,width,px,font)
    value=tostring(value)
    if Theme.textWidth(value,px,font)<=width then return value end
    local chars=require('TW/Text').chars(value)
    repeat
        chars[#chars]=nil
        value=table.concat(chars)..'...'
    until #chars==0 or Theme.textWidth(value,px,font)<=width
    return value
end
-- Word wrap into at most lines lines; the last line is shortened.
function Theme.wrap(value,width,px,lines,font)
    local result,line={},''
    for word in tostring(value):gmatch('%S+') do
        local candidate=line=='' and word or line..' '..word
        if line~='' and Theme.textWidth(candidate,px,font)>width then
            result[#result+1]=line
            line=word
        else
            line=candidate
        end
    end
    if line~='' then result[#result+1]=line end
    if #result>lines then
        local rest={}
        for i=lines,#result do rest[#rest+1]=result[i] end
        for i=#result,lines,-1 do result[i]=nil end
        result[lines]=table.concat(rest,' ')
    end
    for i,v in ipairs(result) do result[i]=Theme.fit(v,width,px,font) end
    return result
end

function Theme.fill(ui,x,y,w,h,c,a) ui:drawRect(x,y,w,h,a or 1,c.r,c.g,c.b) end
function Theme.frame(ui,x,y,w,h,c,a,t)
    t=t or 1
    ui:drawRect(x,y,w,t,a or 1,c.r,c.g,c.b)
    ui:drawRect(x,y+h-t,w,t,a or 1,c.r,c.g,c.b)
    ui:drawRect(x,y+t,t,h-2*t,a or 1,c.r,c.g,c.b)
    ui:drawRect(x+w-t,y+t,t,h-2*t,a or 1,c.r,c.g,c.b)
end
-- Vertical gradient as a few bands (the bar fill of the stats rows).
function Theme.gradient(ui,x,y,w,h,top,bottom,a,steps)
    steps=math.max(1,math.min(steps or 6,math.floor(h)))
    local band=h/steps
    for i=0,steps-1 do
        local f=i/math.max(1,steps-1)
        local yy=math.floor(y+i*band)
        local hh=math.floor(y+(i+1)*band)-yy
        ui:drawRect(x,yy,w,hh,a or 1,top.r+(bottom.r-top.r)*f,top.g+(bottom.g-top.g)*f,top.b+(bottom.b-top.b)*f)
    end
end

-- Solid quadrilateral: ISUIElement.drawPolygon goes through the element's own offset.
local function quad(ui,x1,y1,x2,y2,x3,y3,x4,y4,c,a)
    ui:drawPolygon(nil,x1,y1,x2,y2,x3,y3,x4,y4,c.r,c.g,c.b,a)
end
Theme.quad=quad
function Theme.line(ui,x1,y1,x2,y2,width,c,a)
    local dx,dy=x2-x1,y2-y1
    local length=math.sqrt(dx*dx+dy*dy)
    if length<0.5 then return end
    local nx,ny=-dy/length*width/2,dx/length*width/2
    quad(ui,x1+nx,y1+ny,x2+nx,y2+ny,x2-nx,y2-ny,x1-nx,y1-ny,c,a)
end
-- Disc as a fan of quads (each quad covers two sectors).
local DISC=16
function Theme.disc(ui,x,y,r,c,a)
    for i=0,DISC-1,2 do
        local a1,a2,a3=i/DISC*math.pi*2,(i+1)/DISC*math.pi*2,(i+2)/DISC*math.pi*2
        quad(ui,x,y,x+math.cos(a1)*r,y+math.sin(a1)*r,x+math.cos(a2)*r,y+math.sin(a2)*r,x+math.cos(a3)*r,y+math.sin(a3)*r,c,a)
    end
end
function Theme.ring(ui,x,y,r,width,c,a)
    local inner=math.max(0,r-width)
    for i=0,DISC-1 do
        local a1,a2=i/DISC*math.pi*2,(i+1)/DISC*math.pi*2
        local c1,s1,c2,s2=math.cos(a1),math.sin(a1),math.cos(a2),math.sin(a2)
        quad(ui,x+c1*inner,y+s1*inner,x+c1*r,y+s1*r,x+c2*r,y+s2*r,x+c2*inner,y+s2*inner,c,a)
    end
end
-- Arc of a ring between two angles (radians, clockwise on screen).
function Theme.arc(ui,x,y,r,width,from,to,c,a)
    local inner=math.max(0,r-width)
    local steps=math.max(2,math.ceil(math.abs(to-from)/(math.pi*2)*32))
    for i=0,steps-1 do
        local a1=from+(to-from)*i/steps
        local a2=from+(to-from)*(i+1)/steps
        local c1,s1,c2,s2=math.cos(a1),math.sin(a1),math.cos(a2),math.sin(a2)
        quad(ui,x+c1*inner,y+s1*inner,x+c1*r,y+s1*r,x+c2*r,y+s2*r,x+c2*inner,y+s2*inner,c,a)
    end
end
-- Tarkov's mount points: a shaded white ball for a fitted part, a ring for an empty slot, a blue
-- marker for a vital (required) slot.
function Theme.pointFilled(ui,x,y,r,a)
    Theme.disc(ui,x,y,r,Theme.BLACK,0.35*(a or 1))
    Theme.disc(ui,x,y,r*0.92,Theme.POINT,a)
    Theme.disc(ui,x-r*0.22,y-r*0.25,r*0.45,Theme.WHITE,0.55*(a or 1))
end
function Theme.pointEmpty(ui,x,y,r,a)
    Theme.ring(ui,x,y,r+1,math.max(2,r*0.34),Theme.BLACK,0.45*(a or 1))
    Theme.ring(ui,x,y,r,math.max(1.5,r*0.22),Theme.POINT,a)
end
function Theme.pointVital(ui,x,y,r,a)
    Theme.disc(ui,x,y,r*1.08,Theme.BLACK,0.5*(a or 1))
    Theme.disc(ui,x,y,r,Theme.VITAL,a)
    Theme.disc(ui,x,y,r*0.68,Theme.VITAL_DARK,a)
    Theme.ring(ui,x,y,r*0.5,math.max(1,r*0.14),Theme.VITAL,(a or 1)*0.9)
end
-- Corner brackets around a box (the hovered part on the model).
function Theme.corners(ui,x1,y1,x2,y2,size,t,c,a)
    local l=math.max(1,math.min(size,(x2-x1)/2,(y2-y1)/2))
    for _,corner in ipairs({{x1,y1,1,1},{x2,y1,-1,1},{x1,y2,1,-1},{x2,y2,-1,-1}}) do
        local x,y,sx,sy=corner[1],corner[2],corner[3],corner[4]
        ui:drawRect(sx>0 and x or x-l,sy>0 and y or y-t,l,t,a,c.r,c.g,c.b)
        ui:drawRect(sx>0 and x or x-t,sy>0 and y or y-l,t,l,a,c.r,c.g,c.b)
    end
end
-- Diagonal hatch inside a rectangle (the "worse than the base" part of a stats bar).
function Theme.hatch(ui,x,y,w,h,light,dark,a)
    if w<=0 or h<=0 then return end
    Theme.fill(ui,x,y,w,h,dark,a)
    local step=math.max(4,math.floor(h/3))
    local stripe=math.max(2,math.floor(step/2))
    ui:setStencilRect(x,y,w,h)
    for sx=x-h,x+w,step do
        quad(ui,sx,y+h,sx+stripe,y+h,sx+stripe+h,y,sx+h,y,light,a)
    end
    ui:clearStencilRect()
end
-- Inspect window backdrop: dark diagonal stripes.
function Theme.stripes(ui,x,y,w,h,a)
    Theme.fill(ui,x,y,w,h,Theme.STRIPE_A,a)
    local step=math.max(8,math.floor(h/24))
    ui:setStencilRect(x,y,w,h)
    for sx=x-h,x+w,step*2 do
        quad(ui,sx,y+h,sx+step,y+h,sx+step+h,y,sx+h,y,Theme.STRIPE_B,a)
    end
    ui:clearStencilRect()
end

-- Textures of the screens (tools/make_ui_assets.py, Tarkov sprites where the game has them).
local cache={}
function Theme.texture(name)
    if cache[name]==nil then cache[name]=getTexture('media/textures/TW_TK_'..name..'.png') or false end
    return cache[name] or nil
end
function Theme.icon(ui,name,x,y,w,h,c,a)
    local t=Theme.texture(name)
    if not t then return false end
    c=c or Theme.WHITE
    ui:drawTextureScaledAspect2(t,x,y,w,h,a or 1,c.r,c.g,c.b)
    return true
end

return Theme
