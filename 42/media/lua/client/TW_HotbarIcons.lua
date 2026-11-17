-- Hotbar icons stay in their slot (bug wave 324, the user's item 3, 24.10.2026). Vanilla
-- ISHotbar:render draws an item's texture at its own size, placed at slotX+width/2 for 32 px
-- icons; the pack's Tarkov icons are 256x128 canvases, so a weapon dragged onto the hotbar spilled
-- far over its 60x60 slot (an attached one too, and the faded icon of a free slot). While the
-- hotbar renders, a texture larger than the slot is put back into the slot it was placed for and
-- scaled to fit it, aspect kept, as the inventory and radial menus already draw these icons.
-- Vanilla-sized icons and the small equipped mark keep vanilla's own call.
require "Hotbar/ISHotbar"

local PAD=4
local drawTexture=ISHotbar.drawTexture

local function drawFitted(self,tex,x,y,a,r,g,b)
    local slotW,slotH=self.slotWidth,self.slotHeight
    if not tex or not slotW or not slotH or (tex:getWidth()<=slotW and tex:getHeight()<=slotH) then
        return drawTexture(self,tex,x,y,a,r,g,b)
    end
    local slotX=x-tex:getWidth()/2
    self:drawTextureScaledAspect(tex,slotX+PAD,self.margins+1+PAD,slotW-2*PAD,slotH-2*PAD,a,r or 1,g or 1,b or 1)
end

local render=ISHotbar.render
function ISHotbar:render()
    local own=rawget(self,'drawTexture')
    self.drawTexture=drawFitted
    render(self)
    self.drawTexture=own
end
