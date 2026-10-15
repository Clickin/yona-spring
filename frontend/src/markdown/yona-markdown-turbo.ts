// Share the same Turbo instance as issue navigation; native save forms stay opted out.
// @ts-expect-error Distribution is copied by Gradle and imported by its public URL.
import * as Turbo from '/javascripts/turbo/turbo.es2017-esm.js';

Turbo.session.drive = false;
Turbo.config.forms.mode = 'optin';
