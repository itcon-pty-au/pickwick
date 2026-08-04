# Community whitelists

Curated, themed channel lists shared by Pickwick families. Import one into the
app, then prune and extend it — your kid, your call.

## Importing a list

1. Open the list file here on GitHub and copy the page URL
   (e.g. `https://github.com/itcon-pty-au/pickwick/blob/main/whitelists/science-explorers.txt`).
2. Parent settings → **Channels & playlists** → import from link → paste it.
   The app accepts the GitHub page URL directly (it converts it to the raw file
   itself).

Importing **adds** the list's entries to your whitelist; it never removes
anything you already curated.

## Format

One source per line. Blank lines and everything after `#` are ignored.

```
# Any of these forms work:
https://www.youtube.com/@somehandle
https://www.youtube.com/channel/UCxxxxxxxxxxxxxxxxxxxxxx
https://www.youtube.com/user/legacyname
https://www.youtube.com/playlist?list=PLxxxxxxxx
UCxxxxxxxxxxxxxxxxxxxxxx | Optional Display Name
https://www.youtube.com/playlist?list=PLxxxxxxxx | Optional Display Name   # comment
```

Channel IDs (`UC…`) are the most future-proof form — handles can be renamed.
An optional `| Display Name` sets the label shown on the kid's grid.

## Contributing a list

Add a `<theme>.txt` file via PR, or open a
["Suggest a channel list"](https://github.com/itcon-pty-au/pickwick/issues/new?template=suggest-channels.yml)
issue if you don't use git. Guidelines:

- **Theme it** — "dinosaurs, ages 4–7" beats "good channels". Name the file
  after the theme and put the theme + age range in the header comment.
- **Only list what your family actually watches.** Every entry should be
  something you'd let your own kid see unsupervised — that's the whole product.
- Prefer channel IDs or clean URLs (no `?si=` tracking parameters).
- Label playlists — raw playlist IDs are meaningless on the kid's grid.

Lists are curation opinions, not endorsements; review anything you import.
