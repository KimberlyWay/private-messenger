# Third-party source

`app/src/main/java/uk/mywisp/material/` contains Google's Material Color Utilities,
from https://github.com/material-foundation/material-color-utilities at commit
`bd811e4ca2ab94c4ce3cadc89c19b1820b9704b3` (Apache License 2.0).

Local modifications: Java packages and internal imports are relocated under
`uk.mywisp.material`; AndroidX nullability and Error Prone annotations/imports
are omitted. Color algorithms are unchanged. See
[the full license](licenses/material-color-utilities-LICENSE).

The application uses the phone palette specification `SPEC_2026` for custom
themes. The system wallpaper palette remains provided by Android. Other
dependencies retain their respective licenses.
