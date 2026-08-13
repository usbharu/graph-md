/// <reference types="astro/client" />

declare module "virtual:graphmd/site" {
  const site: import("./lib/site").SiteData;
  export default site;
}
