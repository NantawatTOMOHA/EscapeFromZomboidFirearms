-- Frosted glass behind the Tarkov weapon screens: the game's own frame, blurred, desaturated and
-- darkened towards Tarkov's cold blue (the Java part, FrostedBackdrop, copies and blurs the frame
-- where the game composites the world). Without the Java part (or before its first copy) a dark
-- plate with a vignette keeps the screens readable.
local F={}
-- Tint r, g, b and amount, darken, desaturate, vignette (measured against the reference: the
-- hideout behind Tarkov's modding screen reads as dark slate blue, about 18/30/40 RGB).
F.LOOK={0.04,0.10,0.17,0.36,0.40,0.50,0.75}

function F.draw(ui,x,y,w,h)
    local zb=rawget(_G,'TWZB')
    if zb and zb.frostedBackdrop and not F.off then
        local l=F.LOOK
        local ok,ready=pcall(zb.frostedBackdrop,ui:getAbsoluteX()+x,ui:getAbsoluteY()+y,w,h,l[1],l[2],l[3],l[4],l[5],l[6],l[7])
        if ok and ready then F.blurred=true; return true end
        if not ok then F.off=true; print('[TW] Frosted backdrop unavailable: '..tostring(ready)) end
    end
    F.blurred=false
    ui:drawRect(x,y,w,h,0.88,0.03,0.045,0.06)
    -- Vignette: darker bands towards the edges.
    local band=math.floor(math.min(w,h)*0.08)
    for i=0,3 do
        local a=0.10*(4-i)/4
        ui:drawRect(x,y+i*band/4,w,band/4,a,0,0,0)
        ui:drawRect(x,y+h-(i+1)*band/4,w,band/4,a,0,0,0)
    end
    return false
end

return F
