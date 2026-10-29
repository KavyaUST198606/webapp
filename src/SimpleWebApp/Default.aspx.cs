using System;

namespace SimpleWebApp
{
    public partial class Default : System.Web.UI.Page
    {
        protected void Page_Load(object sender, EventArgs e)
        {
            string text = Request.QueryString["text"] ?? "hello";
            Response.ContentType = "text/plain";
            Response.Write(EchoHelper.Echo(text));
        }
    }
}
