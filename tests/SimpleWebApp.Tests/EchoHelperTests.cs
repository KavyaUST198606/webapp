using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace SimpleWebApp.Tests
{
    [TestClass]
    public class EchoHelperTests
    {
        [TestMethod]
        public void Echo_PrefixesText()
        {
            Assert.AreEqual("Echo: hi", EchoHelper.Echo("hi"));
        }
    }
}
