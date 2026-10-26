"""Render the app's code-native microphone mark for Windows packaging."""
from pathlib import Path
from PIL import Image, ImageDraw

root = Path(__file__).resolve().parents[1] / "desktop/assets"
root.mkdir(parents=True, exist_ok=True)
image = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0))
draw = ImageDraw.Draw(image)
draw.rounded_rectangle((32, 32, 992, 992), radius=240, fill="#183e30")
draw.rounded_rectangle((382, 205, 642, 610), radius=130, fill="#b4efd0")
draw.arc((272, 323, 752, 775), 0, 180, fill="#b4efd0", width=55)
draw.line((272, 465, 272, 550), fill="#b4efd0", width=55)
draw.line((752, 465, 752, 550), fill="#b4efd0", width=55)
draw.line((512, 756, 512, 839), fill="#b4efd0", width=55)
draw.rounded_rectangle((390, 816, 634, 872), radius=28, fill="#b4efd0")
image.save(root / "icon.png")
image.save(root / "icon.ico", sizes=[(16,16),(32,32),(48,48),(64,64),(128,128),(256,256)])
